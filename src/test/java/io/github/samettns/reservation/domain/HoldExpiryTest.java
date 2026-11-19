package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.ConcurrentRunner.Attempt;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Temporary holds: HELD -> CONFIRMED (payment) or HELD -> EXPIRED (deadline, unit back to its shard).
 * The scheduled sweeper is disabled in tests; sweepers are created and driven explicitly here.
 */
class HoldExpiryTest extends IntegrationTest {

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    List<StockReturnListener> stockReturnListeners;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StockInvariant invariant;

    // --- basic lifecycle -----------------------------------------------------

    @Test
    void reserveCreatesHoldWithDatabaseDeadline() {
        long eventId = events.create("hold", 5);
        Reservation hold = hold(service("atomic"), eventId, "alice");

        assertThat(hold.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(hold.expiresAt()).isBetween(
                OffsetDateTime.now().plus(ReservationService.DEFAULT_HOLD_TTL).minusSeconds(30),
                OffsetDateTime.now().plus(ReservationService.DEFAULT_HOLD_TTL).plusSeconds(30));
        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.held()).isEqualTo(1);
        assertThat(s.remaining()).isEqualTo(4);
    }

    @Test
    void confirmBeforeDeadlineIsFinalAndIdempotent() {
        long eventId = events.create("confirm", 5);
        ReservationService service = service("atomic");
        Reservation hold = hold(service, eventId, "alice");

        assertThat(service.confirm(hold.id())).isInstanceOf(ConfirmOutcome.Confirmed.class);
        assertThat(service.confirm(hold.id())).as("retried payment callback")
                .isInstanceOf(ConfirmOutcome.AlreadyConfirmed.class);

        backdateHeldDeadlines(eventId); // touches HELD rows only
        assertThat(sweeper(200).sweepOnce()).as("a confirmed reservation never expires").isZero();

        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.confirmed()).isEqualTo(1);
        assertThat(s.remaining()).isEqualTo(4);
    }

    @Test
    void expiredHoldReturnsItsUnitToTheSameShard() {
        long eventId = events.create("same-shard", 8, 8); // one unit per shard
        ReservationService service = service("atomic");
        List<Reservation> holds = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            holds.add(hold(service, eventId, "user-" + i));
        }
        assertThat(events.shardQuantities(eventId)).containsOnly(0);

        Reservation victim = holds.get(3);
        setDeadline(victim.id(), Duration.ofSeconds(-1));
        assertThat(sweeper(200).sweepOnce()).isEqualTo(1);

        List<Integer> shards = events.shardQuantities(eventId);
        assertThat(shards.get(victim.shardNo())).as("unit back on shard %d", victim.shardNo()).isEqualTo(1);
        assertThat(shards.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(reservations.findById(victim.id()).orElseThrow().status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(service.confirm(victim.id())).isInstanceOf(ConfirmOutcome.Expired.class);

        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.held()).isEqualTo(7);
        assertThat(s.expired()).isEqualTo(1);
    }

    @Test
    void paymentAfterDeadlineIsRejectedEvenBeforeTheSweeperRuns() {
        long eventId = events.create("late-payment", 1);
        ReservationService service = service("atomic");
        Reservation hold = hold(service, eventId, "alice");
        setDeadline(hold.id(), Duration.ofSeconds(-1));

        assertThat(service.confirm(hold.id())).isInstanceOf(ConfirmOutcome.Expired.class);
        assertThat(reservations.findById(hold.id()).orElseThrow().status())
                .as("still HELD in the DB until the sweeper runs").isEqualTo(ReservationStatus.HELD);

        assertThat(sweeper(200).sweepOnce()).isEqualTo(1);
        assertThat(invariant.assertConsistent(eventId).remaining()).isEqualTo(1);
        assertThat(service.reserve(eventId, "bob", "bob-key-" + eventId))
                .as("the returned unit can be sold again").isInstanceOf(ReservationOutcome.Created.class);
    }

    // --- several sweepers in parallel (one per app instance) ---------------

    /**
     * Three sweepers (as on three app instances) expire 3,000 overdue holds at the same time.
     * Each hold must be expired exactly once and each unit returned exactly once, to its shard.
     */
    @Test
    void threeParallelSweepersExpireEveryHoldExactlyOnce() {
        int holdsCount = 3_000;
        long eventId = events.create("multi-sweeper", holdsCount, 4); // 750 units per shard
        ReservationService service = service("atomic");
        List<Attempt<ReservationOutcome>> created = ConcurrentRunner.run(holdsCount,
                i -> service.reserve(eventId, "user-" + i, "event-" + eventId + "-key-" + i));
        assertThat(created).allMatch(a -> a.value() instanceof ReservationOutcome.Created);
        assertThat(events.shardQuantities(eventId)).containsOnly(0);
        backdateHeldDeadlines(eventId);

        List<HoldExpirySweeper> instances = List.of(sweeper(50), sweeper(50), sweeper(50));
        List<Attempt<Integer>> runs = ConcurrentRunner.run(3, i -> instances.get(i).sweepOnce());
        List<Integer> perInstance = runs.stream().map(Attempt::value).toList();
        System.out.printf("[3 sweepers] expired per instance: %s%n", perInstance);

        assertThat(runs).noneMatch(Attempt::failed);
        assertThat(perInstance.stream().mapToInt(Integer::intValue).sum())
                .as("every hold expired exactly once across all instances").isEqualTo(holdsCount);
        assertThat(perInstance).as("work was split, no instance idle").allMatch(n -> n > 0);
        assertThat(events.shardQuantities(eventId)).as("750 units back on every shard").containsOnly(750);

        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.expired()).isEqualTo(holdsCount);
        assertThat(s.held()).isZero();
        assertThat(s.remaining()).isEqualTo(holdsCount);
    }

    // --- payment vs expiry: who wins? -----------------------------------------

    /**
     * Sweeper wins. The payment transaction started BEFORE the deadline (so for it the hold is
     * still payable: {@code expires_at > now()}), but the sweeper locked and expired the row first.
     * The payment waits for the lock; after the sweeper commits, PostgreSQL re-checks
     * {@code status = 'HELD'}, finds EXPIRED and updates nothing.
     */
    @Test
    void raceSweeperLocksFirstThenPaymentFails() throws Exception {
        long eventId = events.create("race-sweeper-wins", 1);
        Reservation hold = hold(service("atomic"), eventId, "alice");
        Duration untilDeadline = Duration.ofMillis(1500);
        setDeadline(hold.id(), untilDeadline);

        CountDownLatch paymentTxStarted = new CountDownLatch(1);
        CountDownLatch runPayment = new CountDownLatch(1);
        AtomicReference<Optional<Reservation>> paymentResult = new AtomicReference<>();
        Thread payment = Thread.ofVirtual().start(() -> tx.executeWithoutResult(status -> {
            jdbc.sql("SELECT 1").query(Integer.class).single(); // freezes now() before the deadline
            paymentTxStarted.countDown();
            await(runPayment);
            paymentResult.set(reservations.confirmIfHeld(hold.id()));
        }));
        paymentTxStarted.await();
        Thread.sleep(untilDeadline.plusMillis(300)); // deadline has passed for the sweeper

        CountDownLatch sweptNotCommitted = new CountDownLatch(1);
        CountDownLatch commitSweep = new CountDownLatch(1);
        AtomicInteger swept = new AtomicInteger(-1);
        HoldExpirySweeper sweeper = sweeper(200);
        Thread sweep = Thread.ofVirtual().start(() -> tx.executeWithoutResult(status -> {
            swept.set(sweeper.expireBatch());
            sweptNotCommitted.countDown();
            await(commitSweep);
        }));
        sweptNotCommitted.await();

        runPayment.countDown();
        Thread.sleep(300);
        assertThat(payment.isAlive()).as("payment must be blocked on the sweeper's row lock").isTrue();

        commitSweep.countDown();
        sweep.join();
        payment.join();

        assertThat(swept.get()).isEqualTo(1);
        assertThat(paymentResult.get()).as("payment lost the race").isEmpty();
        assertThat(reservations.findById(hold.id()).orElseThrow().status()).isEqualTo(ReservationStatus.EXPIRED);
        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.remaining()).as("unit returned exactly once").isEqualTo(1);
    }

    /**
     * Payment wins. The payment confirmed the hold before the deadline but has not committed yet.
     * After the deadline the sweeper finds the row still HELD in its snapshot, but the row is
     * locked: SKIP LOCKED skips it instead of waiting (if the sweeper waited, this test would
     * hang). After the payment commits, the row is CONFIRMED and never selected again.
     */
    @Test
    void racePaymentLocksFirstThenSweeperSkipsIt() throws Exception {
        long eventId = events.create("race-payment-wins", 1);
        Reservation hold = hold(service("atomic"), eventId, "alice");
        Duration untilDeadline = Duration.ofMillis(1500);
        setDeadline(hold.id(), untilDeadline);

        CountDownLatch confirmedNotCommitted = new CountDownLatch(1);
        CountDownLatch commitPayment = new CountDownLatch(1);
        AtomicReference<Optional<Reservation>> paymentResult = new AtomicReference<>();
        Thread payment = Thread.ofVirtual().start(() -> tx.executeWithoutResult(status -> {
            paymentResult.set(reservations.confirmIfHeld(hold.id()));
            confirmedNotCommitted.countDown();
            await(commitPayment);
        }));
        confirmedNotCommitted.await();
        Thread.sleep(untilDeadline.plusMillis(300)); // deadline passed while the payment holds the lock

        HoldExpirySweeper sweeper = sweeper(200);
        int sweptWhileLocked = CompletableFuture.supplyAsync(sweeper::sweepOnce).get(5, TimeUnit.SECONDS);

        commitPayment.countDown();
        payment.join();
        int sweptAfterCommit = sweeper.sweepOnce();

        assertThat(paymentResult.get()).as("payment won").isPresent();
        assertThat(sweptWhileLocked).as("locked row skipped, not waited for").isZero();
        assertThat(sweptAfterCommit).as("confirmed row never expires").isZero();
        assertThat(reservations.findById(hold.id()).orElseThrow().status()).isEqualTo(ReservationStatus.CONFIRMED);
        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.confirmed()).isEqualTo(1);
        assertThat(s.remaining()).as("no unit returned for a paid hold").isZero();
    }

    /**
     * Uncontrolled timing: 300 holds expire 1 s from now; payments arrive at random times in
     * [0, 2 s] while three sweepers keep running. Whoever wins, the payment response must match
     * the final state, and stock must add up.
     */
    @Test
    void raceStressPaymentsAndSweepersAgreeOnEveryHold() throws Exception {
        int n = 300;
        long eventId = events.create("race-stress", n, 4);
        ReservationService service = service("atomic");
        List<Reservation> holds = ConcurrentRunner.run(n,
                        i -> service.reserve(eventId, "user-" + i, "event-" + eventId + "-key-" + i))
                .stream().map(a -> ((ReservationOutcome.Created) a.value()).reservation()).toList();
        jdbc.sql("UPDATE reservations SET expires_at = now() + interval '1 second' WHERE event_id = :id")
                .param("id", eventId).update();

        List<HoldExpirySweeper> instances = List.of(sweeper(20), sweeper(20), sweeper(20));
        long sweepUntil = System.nanoTime() + Duration.ofMillis(2500).toNanos();
        List<Thread> sweepers = instances.stream().map(s -> Thread.ofVirtual().start(() -> {
            while (System.nanoTime() < sweepUntil) {
                s.sweepOnce();
                sleepQuietly(20);
            }
        })).toList();

        List<Attempt<ConfirmOutcome>> payments = ConcurrentRunner.run(n, i -> {
            sleepQuietly(ThreadLocalRandom.current().nextInt(2000));
            return service.confirm(holds.get(i).id());
        });
        for (Thread t : sweepers) {
            t.join();
        }
        sweeper(200).sweepOnce(); // anything left over after the deadline

        Map<UUID, ReservationStatus> finalStatus = holds.stream().collect(Collectors.toMap(
                Reservation::id, r -> reservations.findById(r.id()).orElseThrow().status()));
        long paid = 0;
        long late = 0;
        for (int i = 0; i < n; i++) {
            ConfirmOutcome outcome = payments.get(i).value();
            ReservationStatus status = finalStatus.get(holds.get(i).id());
            if (outcome instanceof ConfirmOutcome.Confirmed) {
                paid++;
                assertThat(status).as("told 'paid' -> must be CONFIRMED").isEqualTo(ReservationStatus.CONFIRMED);
            } else {
                late++;
                assertThat(outcome).isInstanceOf(ConfirmOutcome.Expired.class);
                assertThat(status).as("told 'too late' -> must be EXPIRED").isEqualTo(ReservationStatus.EXPIRED);
            }
        }
        System.out.printf("[payment vs expiry stress] paid=%d late=%d%n", paid, late);
        assertThat(paid).as("both sides of the race happened").isPositive();
        assertThat(late).isPositive();

        StockSnapshot s = invariant.assertConsistent(eventId);
        assertThat(s.held()).isZero();
        assertThat(s.confirmed()).isEqualTo(paid);
        assertThat(s.expired()).isEqualTo(late);
        assertThat(s.remaining()).as("exactly one unit back per expired hold").isEqualTo(late);
    }

    // --- Redis gate stays in sync -------------------------------------------

    @Test
    void expiredHoldIsGivenBackToTheRedisGateToo() {
        long eventId = events.create("redis-expiry", 1);
        ReservationService service = service("redis");
        Reservation hold = hold(service, eventId, "alice");
        assertThat(service.reserve(eventId, "bob", "bob-1-" + eventId)).isInstanceOf(ReservationOutcome.SoldOut.class);

        setDeadline(hold.id(), Duration.ofSeconds(-1));
        assertThat(sweeper(200).sweepOnce()).isEqualTo(1);

        invariant.assertRedisMatchesDatabase(eventId); // Redis back to 1, like the DB
        assertThat(service.reserve(eventId, "bob", "bob-2-" + eventId))
                .as("Redis must not keep saying 'sold out'").isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertConsistent(eventId);
    }

    // --- helpers -------------------------------------------------------------

    private ReservationService service(String strategyName) {
        ReservationStrategy strategy = strategies.stream()
                .filter(s -> s.name().equals(strategyName)).findFirst().orElseThrow();
        return new ReservationService(strategy, reservations, events, tx);
    }

    /** A new sweeper object = what one app instance runs. */
    private HoldExpirySweeper sweeper(int batchSize) {
        return new HoldExpirySweeper(reservations, events, stockReturnListeners, tx, batchSize, new SimpleMeterRegistry());
    }

    private static Reservation hold(ReservationService service, long eventId, String user) {
        ReservationOutcome outcome = service.reserve(eventId, user, "event-" + eventId + "-" + user);
        assertThat(outcome).isInstanceOf(ReservationOutcome.Created.class);
        return ((ReservationOutcome.Created) outcome).reservation();
    }

    /** Moves one reservation's deadline relative to the DATABASE clock. */
    private void setDeadline(UUID reservationId, Duration fromNow) {
        jdbc.sql("UPDATE reservations SET expires_at = now() + :ms * interval '1 millisecond' WHERE id = :id")
                .param("ms", fromNow.toMillis()).param("id", reservationId).update();
    }

    private void backdateHeldDeadlines(long eventId) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE event_id = :id AND status = 'HELD'")
                .param("id", eventId).update();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
