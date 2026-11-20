package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ContentionException;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5b: idempotency claims with an IN_PROGRESS state, and the two "sold out" shortcuts
 * (Redis counter, versioned marker on the event) including their reset when expired holds
 * return stock.
 */
class IdempotencyAndSoldOutTest extends IntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);

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

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    // --- idempotency claims ---------------------------------------------------

    @Test
    void liveClaimOfTheSameRequestAnswersInProgressWithoutTouchingStock() {
        long eventId = events.create("in-progress", 5);
        assertThat(idempotency.claim("alice", "k-" + eventId, eventId, LEASE)).isPresent(); // a twin is "working"

        ReservationOutcome outcome = service("atomic").reserve(eventId, "alice", "k-" + eventId);

        assertThat(outcome).isInstanceOf(ReservationOutcome.InProgress.class);
        assertThat(invariant.assertConsistent(eventId).remaining()).isEqualTo(5);
    }

    @Test
    void abandonedClaimOfACrashedRequestIsTakenOver() {
        long eventId = events.create("abandoned", 5);
        assertThat(idempotency.claim("alice", "k-" + eventId, eventId, Duration.ofSeconds(-1))).isPresent(); // lease already over

        ReservationOutcome outcome = service("atomic").reserve(eventId, "alice", "k-" + eventId);

        assertThat(outcome).isInstanceOf(ReservationOutcome.Created.class);
        assertThat(idempotency.find("alice", "k-" + eventId).orElseThrow().status())
                .isEqualTo(IdempotencyRepository.Status.COMPLETED);
    }

    @Test
    void claimForAnotherEventIsRejected() {
        long first = events.create("first", 5);
        long second = events.create("second", 5);
        assertThat(service("atomic").reserve(first, "alice", "shared-" + first)).isInstanceOf(ReservationOutcome.Created.class);

        assertThatThrownBy(() -> service("atomic").reserve(second, "alice", "shared-" + first))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(invariant.assertConsistent(second).remaining()).isEqualTo(5);
    }

    @Test
    void requestsWithoutSideEffectsGiveTheirKeyBack() {
        long soldOutEvent = events.create("no-stock", 0);
        assertThat(service("atomic").reserve(soldOutEvent, "alice", "k-" + soldOutEvent))
                .isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(idempotency.find("alice", "k-" + soldOutEvent)).as("sold out: key released").isEmpty();

        long eventId = events.create("contended", 5);
        // a strategy that always reports contention (503)
        ReservationStrategy alwaysContended = new ReservationStrategy() {
            @Override
            public String name() {
                return "always-contended";
            }

            @Override
            public OptionalInt tryAcquireOne(long id) {
                throw new ContentionException(id, "test");
            }
        };
        assertThatThrownBy(() -> service(alwaysContended).reserve(eventId, "bob", "k-" + eventId))
                .isInstanceOf(ContentionException.class);
        assertThat(idempotency.find("bob", "k-" + eventId)).as("503: key released, a retry may succeed").isEmpty();
        assertThat(service("atomic").reserve(eventId, "bob", "k-" + eventId)).isInstanceOf(ReservationOutcome.Created.class);
    }

    // --- sharding "sold out" marker -------------------------------------------

    /**
     * 16 shards, 16 units. After the sellout, the first refused request probes all shards and sets
     * the marker; later requests are refused from the marker without touching any stock row. An
     * expired hold returns a unit: the marker is invalidated and the unit can be sold again.
     */
    @Test
    void shardedSoldOutMarkerIsSetUsedAndClearedByStockReturn() {
        long eventId = events.create("marker", 16, 16);
        ReservationService service = service("atomic");
        Reservation first = null;
        for (int i = 0; i < 16; i++) {
            Reservation r = ((ReservationOutcome.Created) service.reserve(eventId, "u" + i, "m-" + eventId + "-" + i)).reservation();
            first = first == null ? r : first;
        }
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).isFalse();

        assertThat(service.reserve(eventId, "late-1", "m-" + eventId + "-late-1"))
                .isInstanceOf(ReservationOutcome.SoldOut.class); // probes 16 shards, sets the marker
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).as("marker set").isTrue();

        assertThat(service.reserve(eventId, "late-2", "m-" + eventId + "-late-2"))
                .isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(shortcuts("marker")).as("answered from the marker").isEqualTo(1);

        // A retry of a request that DID succeed must still be replayed, not told "sold out".
        assertThat(service.reserve(eventId, "u0", "m-" + eventId + "-0")).isInstanceOf(ReservationOutcome.Replayed.class);

        expire(first);
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).as("stock return clears the marker").isFalse();
        assertThat(service.reserve(eventId, "late-3", "m-" + eventId + "-late-3"))
                .as("the returned unit is sold again").isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertConsistent(eventId);
    }

    /** The marker is set by compare-and-set on the stock version: a return in between wins. */
    @Test
    void soldOutMarkerIsNotSetIfStockCameBackWhileProbing() {
        long eventId = events.create("marker-race", 0, 4);
        long observed = events.findAvailability(eventId).orElseThrow().stockVersion(); // read before probing
        events.bumpStockVersion(eventId);                                              // a unit came back meanwhile

        assertThat(events.markSoldOut(eventId, observed)).as("stale observation must not mark").isFalse();
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).isFalse();
        assertThat(events.markSoldOut(eventId, observed + 1)).isTrue();
        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).isTrue();
    }

    // --- Redis "sold out" shortcut ----------------------------------------------

    @Test
    void redisShortcutAnswersSoldOutWithoutTheDatabaseAndResetsOnStockReturn() {
        long eventId = events.create("redis-shortcut", 1);
        ReservationService service = service("redis");
        Reservation hold = ((ReservationOutcome.Created) service.reserve(eventId, "alice", "r-" + eventId)).reservation();

        assertThat(service.reserve(eventId, "bob", "r-" + eventId + "-bob")).isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(shortcuts("strategy")).as("Redis counter at 0 answered it").isEqualTo(1);
        assertThat(service.reserve(eventId, "alice", "r-" + eventId))
                .as("the winner's retry is still replayed").isInstanceOf(ReservationOutcome.Replayed.class);

        expire(hold);
        invariant.assertRedisMatchesDatabase(eventId); // counter back to 1 -> shortcut off
        assertThat(service.reserve(eventId, "carol", "r-" + eventId + "-carol"))
                .isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertConsistent(eventId);
    }

    // --- helpers -------------------------------------------------------------

    private ReservationService service(String strategyName) {
        return service(strategies.stream().filter(s -> s.name().equals(strategyName)).findFirst().orElseThrow());
    }

    private ReservationService service(ReservationStrategy strategy) {
        return new ReservationService(strategy, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, LEASE, meters, DbStepLimiter.unlimited(), true, "stock-first");
    }

    private double shortcuts(String source) {
        return meters.get(ReservationService.SHORTCUT_METRIC).tag("source", source).counter().count();
    }

    private void expire(Reservation hold) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", hold.id()).update();
        new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry()).sweepOnce();
    }
}
