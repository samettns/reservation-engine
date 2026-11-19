package io.github.samettns.reservation.reconciliation;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.domain.HoldExpirySweeper;
import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.reconciliation.RedisLock.Lease;
import io.github.samettns.reservation.reconciliation.RedisStockReconciler.WriteResult;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis/DB reconciliation behind a Redis lock, and fencing of a lock holder that lost its lock
 * without knowing it. The scheduled reconciler is disabled in tests; steps are driven explicitly.
 */
class RedisReconcilerTest extends IntegrationTest {

    private static final String NAME = RedisStockReconciler.LOCK_NAME;

    @Autowired
    RedisLock lock;
    @Autowired
    RedisStockReconciler reconciler;
    @Autowired
    StringRedisTemplate redis;
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

    @BeforeEach
    void noLeftoverLock() {
        redis.delete(RedisLock.lockKey(NAME));
    }

    /** A crash between the Redis decrement and the DB outcome leaves Redis LOWER than the DB. */
    @Test
    void correctsACounterThatDriftedBelowTheDatabase() {
        long eventId = events.create("drift", 10);
        ReservationService service = redisService();
        for (int i = 0; i < 3; i++) {
            service.reserve(eventId, "u" + i, "drift-" + eventId + "-" + i);
        }
        redis.opsForValue().decrement(RedisGateStrategy.stockKey(eventId), 2); // two "crashed" callbacks
        assertThat(counter(eventId)).isEqualTo(5);

        assertThat(reconciler.reconcileOnce()).isTrue();

        assertThat(counter(eventId)).as("back to the DB's remaining stock").isEqualTo(7);
        invariant.assertRedisMatchesDatabase(eventId);
    }

    @Test
    void onlyOneInstanceWorksPerRound() {
        long eventId = events.create("exclusive", 10);
        redisService().reserve(eventId, "u", "excl-" + eventId);
        redis.opsForValue().decrement(RedisGateStrategy.stockKey(eventId), 4);

        Lease other = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow(); // another instance is working
        assertThat(reconciler.reconcileOnce()).as("lock busy: skip this round").isFalse();
        assertThat(counter(eventId)).as("untouched").isEqualTo(5);

        assertThat(lock.release(other)).isTrue();
        assertThat(reconciler.reconcileOnce()).isTrue();
        assertThat(counter(eventId)).isEqualTo(9);
    }

    @Test
    void lockIsExclusiveUnderConcurrentAcquisition() {
        List<Optional<Lease>> attempts = ConcurrentRunner.run(50, i -> lock.tryAcquire(NAME, Duration.ofSeconds(10)))
                .stream().map(ConcurrentRunner.Attempt::value).toList();
        assertThat(attempts.stream().filter(Optional::isPresent)).as("exactly one winner").hasSize(1);
    }

    /**
     * The paused lock holder. A takes the lock and reads the DB (7 left). A is paused. Meanwhile an
     * expired hold returns a unit (DB 8, Redis 8). A's lock expires, B takes it and finds everything
     * correct. A wakes up and writes what it read: 7. Without fencing Redis would drop to 7 and one
     * existing unit could never be sold. The Lua script compares A's token with the newest one and
     * rejects the write atomically, even though A does not know it lost the lock.
     */
    @Test
    void staleLockHolderCannotOverwriteTheCounter() throws Exception {
        long eventId = events.create("stale-holder", 10);
        ReservationService service = redisService();
        Reservation willExpire = null;
        for (int i = 0; i < 3; i++) {
            ReservationOutcome o = service.reserve(eventId, "u" + i, "stale-" + eventId + "-" + i);
            willExpire = ((ReservationOutcome.Created) o).reservation();
        }

        Lease a = lock.tryAcquire(NAME, Duration.ofMillis(200)).orElseThrow();
        Map<Long, Long> aRead = reconciler.readTargets();
        assertThat(aRead.get(eventId)).isEqualTo(7);
        // ---- A is paused (GC) ----
        expire(willExpire);                              // a unit comes back: DB 8, Redis 8
        assertThat(counter(eventId)).isEqualTo(8);
        Thread.sleep(300);                               // A's lock expires
        Lease b = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow();
        assertThat(b.fenceToken()).isGreaterThan(a.fenceToken());
        assertThat(reconciler.write(b, eventId, reconciler.readTargets().get(eventId))).isEqualTo(WriteResult.UNCHANGED);
        // ---- A wakes up, still believing it holds the lock ----
        WriteResult stale = reconciler.write(a, eventId, aRead.get(eventId));

        assertThat(stale).isEqualTo(WriteResult.REJECTED_STALE_TOKEN);
        assertThat(counter(eventId)).as("the returned unit stays sellable").isEqualTo(8);
        invariant.assertRedisMatchesDatabase(eventId);
        assertThat(lock.release(a)).as("A cannot free B's lock").isFalse();
        assertThat(lock.tryAcquire(NAME, Duration.ofSeconds(10))).as("B still holds it").isEmpty();
        assertThat(lock.release(b)).isTrue();
    }

    // --- helpers -------------------------------------------------------------

    private ReservationService redisService() {
        ReservationStrategy redisGate = strategies.stream().filter(s -> s.name().equals("redis")).findFirst().orElseThrow();
        return new ReservationService(redisGate, reservations, events, reservationEvents, idempotency, tx);
    }

    private long counter(long eventId) {
        return Long.parseLong(redis.opsForValue().get(RedisGateStrategy.stockKey(eventId)));
    }

    private void expire(Reservation hold) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", hold.id()).update();
        new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry()).sweepOnce();
    }
}
