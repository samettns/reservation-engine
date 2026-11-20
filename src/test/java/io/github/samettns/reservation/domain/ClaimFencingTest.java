package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency claims are leases. If the owner is not dead but merely SLOW (GC pause, slow DB), its
 * lease can expire, another request takes the key over, and then the old owner wakes up and carries
 * on as if it still owned the key. These tests pause request A deterministically between "claimed"
 * and "transaction starts", let the lease expire, let B take over, then resume A.
 *
 * <p>The lease is expired EXPLICITLY ({@link #expireLease}), not by sleeping past it. Leases are
 * judged by the DB's wall clock ({@code now()}), and that clock can step: under WSL2/Docker it was
 * measured going BACKWARDS by 163 ms during a 350 ms sleep, so A's 200 ms lease was still valid
 * from the DB's point of view, B correctly could not take over, and the test failed (about 1 run
 * in 50). The code was right; the test assumed the wall clock follows the JVM's sleep. The leases
 * themselves are long (30 s), so no lease in these tests can run out by accident either.
 */
class ClaimFencingTest extends IntegrationTest {

    private static final Duration LEASE = ReservationService.DEFAULT_IDEMPOTENCY_LEASE;
    private static final String UNIQUE_CONSTRAINT = "uq_reservations_user_idempotency_key";

    @Autowired
    List<ReservationStrategy> strategies;
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

    /** Backstop: with the unique constraint on reservations, a second reservation cannot be inserted. */
    @Test
    void slowOwnerAfterTakeoverCannotCreateASecondReservation() throws Exception {
        long eventId = events.create("slow-owner", 5);
        Outcomes o = slowOwnerScenario(eventId, "alice", "k-" + eventId);

        assertThat(o.takeover()).isInstanceOf(ReservationOutcome.Created.class);
        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(events.findStock(eventId).orElseThrow().remaining()).isEqualTo(4);
        assertThat(o.slowOwner()).as("the slow owner gets the winner's result")
                .isInstanceOf(ReservationOutcome.Replayed.class);
    }

    /**
     * The same scenario with the unique constraint temporarily removed: does the CLAIM PROTOCOL by
     * itself prevent a second reservation? Without fencing it does not (two reservations, two units).
     */
    @Test
    void withoutTheUniqueConstraintOnlyFencingPreventsASecondReservation() throws Exception {
        long eventId = events.create("slow-owner-no-constraint", 5);
        jdbc.sql("ALTER TABLE reservations DROP CONSTRAINT " + UNIQUE_CONSTRAINT).update();
        try {
            Outcomes o = slowOwnerScenario(eventId, "alice", "k-" + eventId);

            assertThat(o.takeover()).isInstanceOf(ReservationOutcome.Created.class);
            assertThat(reservationCount(eventId)).as("one logical request -> one reservation").isEqualTo(1);
            assertThat(events.findStock(eventId).orElseThrow().remaining()).isEqualTo(4);
        } finally {
            // keep the shared test database intact even if this test failed
            jdbc.sql("""
                    DELETE FROM reservations r USING reservations d
                    WHERE r.user_id = d.user_id AND r.idempotency_key = d.idempotency_key AND r.created_at > d.created_at
                    """).update();
            jdbc.sql("ALTER TABLE reservations ADD CONSTRAINT " + UNIQUE_CONSTRAINT
                    + " UNIQUE (user_id, idempotency_key)").update();
        }
    }

    /**
     * A slow owner that ends WITHOUT a side effect (sold out) releases "its" key. Without fencing that
     * deletes the NEW owner's live claim, so a third copy of the request is let in while B is still
     * working. It must instead be told "in progress".
     */
    @Test
    void staleOwnerMustNotReleaseTheNewOwnersClaim() throws Exception {
        long eventId = events.create("stale-release", 0); // every attempt ends sold out (no side effect)
        String key = "k-" + eventId;

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aGo = new CountDownLatch(1);
        Thread a = Thread.ofVirtual().start(() ->
                service(pauseAfterClaim(aClaimed, aGo)).reserve(eventId, "alice", key));
        await(aClaimed);
        expireLease("alice", key);                  // A's lease is over

        CountDownLatch bClaimed = new CountDownLatch(1);
        CountDownLatch bGo = new CountDownLatch(1);
        Thread b = Thread.ofVirtual().start(() ->
                service(pauseAfterClaim(bClaimed, bGo)).reserve(eventId, "alice", key));
        await(bClaimed);                            // B took the key over and is "working"

        aGo.countDown();
        a.join();                                   // A: sold out -> releases the key it believes it owns

        ReservationOutcome c = service(() -> { }).reserve(eventId, "alice", key);
        bGo.countDown();
        b.join();

        assertThat(c).as("B still owns the key, so a third copy must wait")
                .isInstanceOf(ReservationOutcome.InProgress.class);
    }

    /**
     * The case only the TOKEN can decide: A's lease was taken over by B, B is still working
     * (IN_PROGRESS), and A wakes up. By status alone A's claim row looks fine ("IN_PROGRESS"), so A
     * would act on a lease that is no longer its own and B's work would be thrown away. With the
     * token, A stops before touching stock and B, the rightful owner, completes the request.
     */
    @Test
    void staleOwnerMustNotActWhileTheNewOwnerIsStillWorking() throws Exception {
        long eventId = events.create("stale-acts", 5);
        String key = "k-" + eventId;

        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aGo = new CountDownLatch(1);
        AtomicReference<ReservationOutcome> aResult = new AtomicReference<>();
        Thread a = Thread.ofVirtual().start(() ->
                aResult.set(service(pauseAfterClaim(aClaimed, aGo)).reserve(eventId, "alice", key)));
        await(aClaimed);
        expireLease("alice", key);

        CountDownLatch bClaimed = new CountDownLatch(1);
        CountDownLatch bGo = new CountDownLatch(1);
        AtomicReference<ReservationOutcome> bResult = new AtomicReference<>();
        Thread b = Thread.ofVirtual().start(() ->
                bResult.set(service(pauseAfterClaim(bClaimed, bGo)).reserve(eventId, "alice", key)));
        await(bClaimed);   // B owns the key now (token 2) and is "working"

        aGo.countDown();   // A (token 1) wakes up first
        a.join();
        bGo.countDown();
        b.join();

        assertThat(aResult.get()).as("stale owner A must not act").isInstanceOf(ReservationOutcome.InProgress.class);
        assertThat(bResult.get()).as("rightful owner B completes the request").isInstanceOf(ReservationOutcome.Created.class);
        assertThat(reservationCount(eventId)).isEqualTo(1);
        assertThat(events.findStock(eventId).orElseThrow().remaining()).isEqualTo(4);
    }

    // --- helpers -------------------------------------------------------------

    private record Outcomes(ReservationOutcome slowOwner, ReservationOutcome takeover) {
    }

    /** A claims and pauses; A's lease expires; B takes over and finishes; A resumes. */
    private Outcomes slowOwnerScenario(long eventId, String user, String key) throws Exception {
        CountDownLatch aClaimed = new CountDownLatch(1);
        CountDownLatch aGo = new CountDownLatch(1);
        AtomicReference<ReservationOutcome> aResult = new AtomicReference<>();
        Thread a = Thread.ofVirtual().start(() ->
                aResult.set(service(pauseAfterClaim(aClaimed, aGo)).reserve(eventId, user, key)));
        await(aClaimed);
        expireLease(user, key); // "GC pause" long enough for A's lease to run out

        ReservationOutcome b = service(() -> { }).reserve(eventId, user, key); // takes the key over
        aGo.countDown();
        a.join();
        return new Outcomes(aResult.get(), b);
    }

    private ReservationService service(Runnable afterClaim) {
        ReservationStrategy atomic = strategies.stream().filter(s -> s.name().equals("atomic")).findFirst().orElseThrow();
        return new ReservationService(atomic, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, LEASE, new SimpleMeterRegistry(), afterClaim);
    }

    /**
     * Ends the current owner's lease NOW, by the DB's own clock, instead of sleeping past it (the
     * wall clock can step backwards; see the class comment).
     */
    private void expireLease(String user, String key) {
        int updated = jdbc.sql("""
                        UPDATE idempotency_keys SET locked_until = now() - interval '1 second'
                        WHERE user_id = :user AND idempotency_key = :key AND status = 'IN_PROGRESS'
                        """)
                .param("user", user).param("key", key).update();
        assertThat(updated).as("an IN_PROGRESS claim to expire").isEqualTo(1);
    }

    private static Runnable pauseAfterClaim(CountDownLatch claimed, CountDownLatch go) {
        return () -> {
            claimed.countDown();
            await(go);
        };
    }

    private long reservationCount(long eventId) {
        return jdbc.sql("SELECT count(*) FROM reservations WHERE event_id = :id").param("id", eventId)
                .query(Long.class).single();
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
}
