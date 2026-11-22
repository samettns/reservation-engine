package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tx-order=stock-last (the default since stage 8): inside the reservation transaction the hold,
 * the claim's COMPLETED and only then the hot stock UPDATE. It must not change correctness for ANY
 * strategy, sharded or not:
 * <ul>
 *   <li>under concurrency: exactly the stock is sold, one outbox event per hold, every losing key
 *       released, and the Redis gate counter equals the DB's remaining stock;</li>
 *   <li>when the event is sold out the early inserts are rolled back: no hold, no event, no claim
 *       left behind, so the same key works once a unit comes back;</li>
 *   <li>the hold records the shard the unit really came from (it is inserted before the shard is
 *       known).</li>
 * </ul>
 */
class StockLastTransactionTest extends IntegrationTest {

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    List<StockReturnListener> stockReturnListeners;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    ReservationEvents reservationEvents;
    @Autowired
    IdempotencyRepository idempotency;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StockInvariant invariant;

    @ParameterizedTest(name = "{0}, {1} shard(s)")
    @CsvSource({"atomic,1", "atomic,16", "optimistic,1", "optimistic,16", "pessimistic,1", "pessimistic,16",
            "pessimistic-precheck,1", "pessimistic-precheck,16", "redis,1", "redis,16"})
    void keepsTheInvariantUnderConcurrency(String strategy, int shards) {
        long eventId = events.create("stock-last-" + strategy, 50, shards);
        ReservationService service = service(strategy, true);

        List<ConcurrentRunner.Attempt<ReservationOutcome>> attempts = ConcurrentRunner.run(200,
                i -> service.reserve(eventId, "u" + i, "sl-" + eventId + "-" + i));

        assertThat(attempts).noneMatch(ConcurrentRunner.Attempt::failed);
        assertThat(attempts.stream().filter(a -> a.value() instanceof ReservationOutcome.Created).count()).isEqualTo(50);
        invariant.assertHolds(eventId, 50);
        assertThat(count("SELECT count(*) FROM reservations WHERE event_id = :e", eventId))
                .as("sold-out attempts left no hold behind").isEqualTo(50);
        assertThat(count("SELECT count(*) FROM outbox o JOIN reservations r ON r.id = o.aggregate_id WHERE r.event_id = :e", eventId))
                .as("one event per hold, none from rolled-back attempts").isEqualTo(50);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE event_id = :e AND status = 'COMPLETED'", eventId))
                .isEqualTo(50);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE event_id = :e AND status = 'IN_PROGRESS'", eventId))
                .as("every key without a side effect was released").isZero();
        if (strategy.equals("redis")) {
            // A sold-out stock-last transaction ROLLS BACK; the gate's rollback compensation must not
            // give back a decrement the DB never had a unit for (that left Redis above the DB).
            invariant.assertRedisMatchesDatabase(eventId);
        }
    }

    /**
     * Sell out, then one more attempt: with shortcuts OFF it runs the whole stock-last transaction
     * (hold insert + claim COMPLETED, then the empty stock UPDATE) and must roll all of it back.
     * With shortcuts ON the first sold-out answer of a DB strategy also goes through that
     * transaction and writes the marker afterwards. Then one hold expires: the unit goes back to
     * its shard, the marker / Redis shortcut no longer says "sold out", and the SAME key succeeds.
     */
    @ParameterizedTest(name = "{0}, {1} shard(s), shortcuts {2}")
    @CsvSource({"atomic,1,false", "atomic,16,false", "optimistic,1,false", "optimistic,16,false",
            "pessimistic,1,false", "pessimistic,16,false", "pessimistic-precheck,1,false", "pessimistic-precheck,16,false",
            "redis,1,false", "redis,16,false",
            "atomic,1,true", "atomic,16,true", "optimistic,1,true", "optimistic,16,true",
            "pessimistic,1,true", "pessimistic,16,true", "pessimistic-precheck,1,true", "pessimistic-precheck,16,true",
            "redis,1,true", "redis,16,true"})
    void soldOutRollsBackAndTheSameKeyWorksAfterAStockReturn(String strategy, int shards, boolean shortcuts) {
        long eventId = events.create("stock-last-rollback-" + strategy, 16, shards);
        ReservationService service = service(strategy, shortcuts);
        for (int i = 0; i < 16; i++) {
            assertThat(service.reserve(eventId, "buyer" + i, "k-" + eventId + "-" + i))
                    .isInstanceOf(ReservationOutcome.Created.class);
        }
        String lateKey = "late-" + eventId;

        assertThat(service.reserve(eventId, "late", lateKey)).isInstanceOf(ReservationOutcome.SoldOut.class);

        assertThat(reservations.findByIdempotencyKey("late", lateKey)).as("the early hold insert rolled back").isEmpty();
        assertThat(idempotency.find("late", lateKey)).as("the claim was released, not left COMPLETED").isEmpty();
        assertThat(count("SELECT count(*) FROM outbox o JOIN reservations r ON r.id = o.aggregate_id WHERE r.event_id = :e", eventId))
                .isEqualTo(16);
        invariant.assertHolds(eventId, 16);
        boolean marker = events.findAvailability(eventId).orElseThrow().soldOut();
        if (!shortcuts) {
            assertThat(marker).as("no marker without shortcuts").isFalse();
        } else if (!strategy.equals("redis")) {
            assertThat(marker).as("stock-last writes the marker after its rolled-back transaction").isTrue();
        }

        // One hold expires: its unit goes back to the shard recorded on the hold.
        UUID expired = jdbc.sql("""
                        UPDATE reservations SET expires_at = now() - interval '1 second'
                        WHERE id = (SELECT id FROM reservations WHERE event_id = :e ORDER BY id LIMIT 1)
                        RETURNING id""")
                .param("e", eventId).query(UUID.class).single();
        int returnedShard = jdbc.sql("SELECT shard_no FROM reservations WHERE id = :id").param("id", expired)
                .query(Integer.class).single();
        sweeper().sweepOnce();
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).as("marker cleared by the return").isFalse();

        ReservationOutcome retry = service.reserve(eventId, "late", lateKey);

        assertThat(retry).as("same key, after the return").isInstanceOf(ReservationOutcome.Created.class);
        assertThat(((ReservationOutcome.Created) retry).reservation().shardNo())
                .as("the hold points to the shard the unit came from").isEqualTo(returnedShard);
        invariant.assertHolds(eventId, 16);
        assertThat(count("SELECT coalesce(sum(qty), 0) FROM stock WHERE event_id = :e", eventId)).isZero();
    }

    @Test
    void recordsTheShardTheUnitCameFrom() {
        long eventId = events.create("stock-last-shards", 32, 16);              // 2 per shard
        ReservationService service = service("atomic", true);
        ConcurrentRunner.run(32, i -> service.reserve(eventId, "u" + i, "sls-" + eventId + "-" + i));

        // Expire everything: each unit goes back to the shard recorded on its hold.
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE event_id = :e")
                .param("e", eventId).update();
        sweeper().sweepOnce();

        assertThat(jdbc.sql("SELECT qty FROM stock WHERE event_id = :e ORDER BY shard_no").param("e", eventId)
                .query(Integer.class).list())
                .as("every shard back to exactly its own 2 units").containsOnly(2).hasSize(16);
    }

    @Test
    void theOrderIsChosenExplicitlyAndATypoFailsAtStartup() {
        assertThat(ReservationService.isStockLast("stock-last")).isTrue();
        assertThat(ReservationService.isStockLast("stock-first")).isFalse();
        assertThatThrownBy(() -> ReservationService.isStockLast("stock_first"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stock_first");
    }

    private HoldExpirySweeper sweeper() {
        return new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry());
    }

    private long count(String sql, long eventId) {
        return jdbc.sql(sql).param("e", eventId).query(Long.class).single();
    }

    private ReservationService service(String name, boolean shortcuts) {
        ReservationStrategy strategy = strategies.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        return new ReservationService(strategy, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, ReservationService.DEFAULT_IDEMPOTENCY_LEASE,
                new SimpleMeterRegistry(), () -> { }, DbStepLimiter.unlimited(), shortcuts, true);
    }
}
