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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two stage 8 benchmark switches must not change correctness, only cost:
 * <ul>
 *   <li>tx-order=stock-last (inserts first, hot stock UPDATE last): same invariant under
 *       concurrency; a sold-out attempt leaves nothing behind (its inserts are rolled back); with
 *       sharding the hold points to the shard the unit really came from.</li>
 *   <li>soldout-shortcuts.enabled=false: correct answers from the DB, and no sold-out marker.</li>
 * </ul>
 */
class BenchmarkSwitchesTest extends IntegrationTest {

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
    @CsvSource({"atomic,1", "atomic,16", "pessimistic,1", "pessimistic,16", "redis,1", "optimistic,1"})
    void stockLastKeepsTheInvariantUnderConcurrency(String strategy, int shards) {
        long eventId = events.create("stock-last-" + strategy, 50, shards);
        ReservationService service = service(strategy, true, true);

        List<ConcurrentRunner.Attempt<ReservationOutcome>> attempts = ConcurrentRunner.run(200,
                i -> service.reserve(eventId, "u" + i, "sl-" + eventId + "-" + i));

        long created = attempts.stream().filter(a -> a.value() instanceof ReservationOutcome.Created).count();
        assertThat(attempts).noneMatch(ConcurrentRunner.Attempt::failed);
        assertThat(created).isEqualTo(50);
        invariant.assertHolds(eventId, 50);
        assertThat(rows("SELECT count(*) FROM reservations WHERE event_id = :e", eventId))
                .as("sold-out attempts left no reservation behind").isEqualTo(50);
        assertThat(rows("SELECT count(*) FROM outbox o JOIN reservations r ON r.id = o.aggregate_id WHERE r.event_id = :e", eventId))
                .as("one event per hold, none from rolled-back attempts").isEqualTo(50);
    }

    @Test
    void stockLastRecordsTheShardTheUnitCameFrom() {
        long eventId = events.create("stock-last-shards", 32, 16);              // 2 per shard
        ReservationService service = service("atomic", true, true);
        ConcurrentRunner.run(32, i -> service.reserve(eventId, "u" + i, "sls-" + eventId + "-" + i));

        // Expire everything: each unit goes back to the shard recorded on its hold.
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE event_id = :e")
                .param("e", eventId).update();
        new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry()).sweepOnce();

        assertThat(jdbc.sql("SELECT qty FROM stock WHERE event_id = :e ORDER BY shard_no").param("e", eventId)
                .query(Integer.class).list())
                .as("every shard back to exactly its own 2 units").containsOnly(2).hasSize(16);
    }

    @Test
    void withoutShortcutsTheAnswersStayCorrectAndNoMarkerIsWritten() {
        long eventId = events.create("no-shortcuts", 1);
        ReservationService service = service("atomic", false, false);

        assertThat(service.reserve(eventId, "u1", "ns-1-" + eventId)).isInstanceOf(ReservationOutcome.Created.class);
        assertThat(service.reserve(eventId, "u2", "ns-2-" + eventId)).isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(service.reserve(eventId, "u3", "ns-3-" + eventId)).isInstanceOf(ReservationOutcome.SoldOut.class);

        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).as("no marker written").isFalse();
        invariant.assertHolds(eventId, 1);
    }

    private long rows(String sql, long eventId) {
        return jdbc.sql(sql).param("e", eventId).query(Long.class).single();
    }

    private ReservationService service(String name, boolean shortcuts, boolean stockLast) {
        ReservationStrategy strategy = strategies.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        return new ReservationService(strategy, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, ReservationService.DEFAULT_IDEMPOTENCY_LEASE,
                new SimpleMeterRegistry(), () -> { }, DbStepLimiter.unlimited(), shortcuts, stockLast);
    }
}
