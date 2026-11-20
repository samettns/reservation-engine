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
import io.github.samettns.reservation.reconciliation.RedisStockReconciler.Target;
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
import java.util.concurrent.CompletableFuture;

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

        Lease a = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow();
        Map<Long, Target> aRead = reconciler.readTargets();
        assertThat(aRead.get(eventId).dbRemaining()).isEqualTo(7);
        // ---- A is paused (GC) ----
        expire(willExpire);                              // a unit comes back: DB 8, Redis 8
        assertThat(counter(eventId)).isEqualTo(8);
        leaseRunsOut();                                  // A's lock expires
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

    /**
     * A stale write the returns-version guard does NOT catch: it would RAISE the counter. A reads 7,
     * is paused, a reservation commits (DB 6, Redis 6), B takes over. A's write of 7 is a raise, which
     * the version guard allows (raising never creates under-reporting). Only the fencing token stops
     * it; without fencing Redis would claim 7 while the DB has 6.
     */
    @Test
    void staleLockHolderCannotRaiseTheCounterEither() throws Exception {
        long eventId = events.create("stale-raise", 10);
        ReservationService service = redisService();
        for (int i = 0; i < 3; i++) {
            service.reserve(eventId, "u" + i, "stale-raise-" + eventId + "-" + i);
        }

        Lease a = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow();
        Target aRead = reconciler.readTargets().get(eventId);
        assertThat(aRead.dbRemaining()).isEqualTo(7);
        // ---- A is paused ----
        service.reserve(eventId, "u3", "stale-raise-" + eventId + "-3"); // DB 6, Redis 6
        leaseRunsOut();
        Lease b = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow();
        assertThat(reconciler.write(b, eventId, reconciler.readTargets().get(eventId))).isEqualTo(WriteResult.UNCHANGED);
        // ---- A wakes up ----
        assertThat(reconciler.write(a, eventId, aRead)).isEqualTo(WriteResult.REJECTED_STALE_TOKEN);

        assertThat(counter(eventId)).isEqualTo(6);
        invariant.assertRedisMatchesDatabase(eventId);
        assertThat(lock.release(b)).isTrue();
    }

    // --- races of a LEGITIMATE lock holder with live traffic -------------------

    /**
     * Under-reporting, the harmful direction. The round holds the lock legitimately. It reads the DB
     * (0 left), then a hold expires and its unit comes back (DB 1, Redis 1), then the round writes
     * what it read. An absolute SET would put Redis back to 0: the "sold out" shortcut would refuse
     * a unit that exists until the next round. The write would LOWER the counter while the returns
     * version moved, so it is skipped.
     */
    @Test
    void stockReturnDuringARoundIsNotOverwritten() {
        long eventId = events.create("return-race", 1);
        ReservationService service = redisService();
        Reservation hold = ((ReservationOutcome.Created) service.reserve(eventId, "u0", "rr-" + eventId + "-0"))
                .reservation();
        assertThat(counter(eventId)).isZero();

        Lease round = lock.tryAcquire(NAME, Duration.ofSeconds(10)).orElseThrow();
        Target read = reconciler.readTargets().get(eventId);
        assertThat(read.dbRemaining()).isZero();
        expire(hold);                                    // between the round's read and write
        assertThat(counter(eventId)).isEqualTo(1);

        assertThat(reconciler.write(round, eventId, read)).isEqualTo(WriteResult.SKIPPED_RETURN_IN_FLIGHT);
        assertThat(counter(eventId)).as("the returned unit stays visible").isEqualTo(1);
        invariant.assertRedisMatchesDatabase(eventId);
        assertThat(service.reserve(eventId, "u1", "rr-" + eventId + "-1"))
                .as("not a false 'sold out'").isInstanceOf(ReservationOutcome.Created.class);
        assertThat(lock.release(round)).isTrue();
    }

    /**
     * Over-reporting, the safe direction. A reservation has decremented Redis (2 -> 1) and taken the
     * unit in its still-open DB transaction when a round runs: the round reads the committed DB value
     * (2) and raises Redis to 2. After the commit Redis says 2, the DB 1. Nothing can be oversold
     * (the DB has the final say) and the next round brings Redis down to 1.
     */
    @Test
    void inFlightReservationLeavesTheCounterHighUntilTheNextRound() {
        long eventId = events.create("in-flight", 2);
        leaveCounterOneTooHigh(eventId);
        assertThat(counter(eventId)).isEqualTo(2);
        assertThat(dbRemaining(eventId)).isEqualTo(1);

        assertThat(reconciler.reconcileOnce()).isTrue();

        assertThat(counter(eventId)).as("lowered: no return raced this round").isEqualTo(1);
        invariant.assertRedisMatchesDatabase(eventId);
    }

    /** The same surplus, without a round: requests consume it, and the DB keeps the answers right. */
    @Test
    void overReportingIsConsumedByRequestsTheDatabaseTurnsAway() {
        long eventId = events.create("in-flight-drain", 2);
        leaveCounterOneTooHigh(eventId);
        ReservationService service = redisService();

        assertThat(service.reserve(eventId, "u1", "drain-" + eventId + "-1")).isInstanceOf(ReservationOutcome.Created.class);
        assertThat(service.reserve(eventId, "u2", "drain-" + eventId + "-2"))
                .as("Redis let it through, the DB refused it").isInstanceOf(ReservationOutcome.SoldOut.class);

        assertThat(dbRemaining(eventId)).isZero();
        invariant.assertRedisMatchesDatabase(eventId);
    }

    /**
     * Lazy initialisation has the same under-reporting race. The counter is missing (e.g. Redis
     * restarted); initialisation reads the DB (0 left); a hold expires (DB 1) and its INCRBY finds no
     * counter, so it does nothing. Writing 0 now would make the unit unsellable. The returns version
     * moved, so initialisation is skipped, and the next request initialises from the DB (1).
     */
    @Test
    void lazyInitialisationIsSkippedWhenAStockReturnRacesIt() {
        long eventId = events.create("init-race", 1);
        ReservationService service = redisService();
        Reservation hold = ((ReservationOutcome.Created) service.reserve(eventId, "u0", "ir-" + eventId + "-0"))
                .reservation();
        redis.delete(RedisGateStrategy.stockKey(eventId));          // counter lost

        String versionBeforeRead = redisGate().returnsVersion(eventId);
        long read = dbRemaining(eventId);                           // 0
        expire(hold);                                               // DB 1, no counter to increment
        assertThat(redisGate().initialiseCounter(eventId, read, versionBeforeRead)).isEqualTo(-1);
        assertThat(redis.hasKey(RedisGateStrategy.stockKey(eventId))).isFalse();

        assertThat(service.reserve(eventId, "u1", "ir-" + eventId + "-1")).isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertRedisMatchesDatabase(eventId);
    }

    /**
     * A rollback compensation after the counter was lost (Redis restart) must not recreate it: a
     * plain INCR would create "1" for an event with 10 units left, i.e. under-reporting.
     */
    @Test
    void rollbackCompensationDoesNotRecreateALostCounter() {
        long eventId = events.create("lost-counter", 10);
        RedisGateStrategy gate = redisGate();
        try {
            tx.executeWithoutResult(status -> {
                gate.tryAcquireOne(eventId);
                redis.delete(RedisGateStrategy.stockKey(eventId)); // Redis restarted mid-request
                throw new IllegalStateException("request fails after the gate");
            });
        } catch (IllegalStateException expected) {
            // rolled back: the compensation ran
        }
        assertThat(redis.hasKey(RedisGateStrategy.stockKey(eventId))).isFalse();
        assertThat(dbRemaining(eventId)).isEqualTo(10);
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The lock's TTL runs out, which is exactly "Redis deletes the key". Done explicitly instead of
     * sleeping past a short TTL: Redis judges expiry by the wall clock, which can step backwards
     * (measured under WSL2/Docker; it made a lease-based test fail about 1 run in 50).
     */
    private void leaseRunsOut() {
        assertThat(redis.delete(RedisLock.lockKey(NAME))).isTrue();
    }

    /** Event with 2 units: one reservation in flight while a round runs, then committed. Redis 2, DB 1. */
    private void leaveCounterOneTooHigh(long eventId) {
        RedisGateStrategy gate = redisGate();
        tx.executeWithoutResult(status -> {
            assertThat(gate.tryAcquireOne(eventId)).isPresent();     // Redis 2 -> 1, DB row taken, not committed
            assertThat(counter(eventId)).isEqualTo(1);
            // The round on another thread = another connection: it sees the committed DB value, 2.
            assertThat(CompletableFuture.supplyAsync(reconciler::reconcileOnce).join()).isTrue();
            assertThat(counter(eventId)).as("raised over the in-flight decrement").isEqualTo(2);
        });
    }

    private RedisGateStrategy redisGate() {
        return (RedisGateStrategy) strategies.stream().filter(s -> s.name().equals("redis")).findFirst().orElseThrow();
    }

    private long dbRemaining(long eventId) {
        return jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM stock WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

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
