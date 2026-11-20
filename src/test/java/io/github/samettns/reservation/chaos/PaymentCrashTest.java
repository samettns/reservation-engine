package io.github.samettns.reservation.chaos;

import io.github.samettns.reservation.domain.ConfirmOutcome;
import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.domain.ReservationStatus;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.support.StockInvariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The instance handling a payment dies. Seen from the database, an instance dying is its sessions
 * disappearing: an open transaction is rolled back, a committed one stays. Two moments:
 * <ol>
 *   <li>after the confirm UPDATE, before COMMIT: nothing of it may survive, and the payment
 *       provider's retry (to another instance) must confirm the hold;</li>
 *   <li>after COMMIT, before the answer reached the caller: the retry must NOT confirm twice or
 *       emit a second ReservationConfirmed event.</li>
 * </ol>
 * (The real container kill, through nginx with three instances, is bench/verify-kill-during-payment.sh.)
 */
class PaymentCrashTest extends ChaosIntegrationTest {

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
    @Autowired
    StockInvariant invariant;

    @Test
    void instanceDiesBeforeCommitThenTheRetryConfirms() throws Exception {
        long eventId = events.create("pay-crash-before-commit", 1);
        Reservation hold = newHold(eventId);

        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch die = new CountDownLatch(1);
        AtomicInteger backendPid = new AtomicInteger();
        AtomicReference<Throwable> paymentFailure = new AtomicReference<>();
        Thread payment = Thread.ofVirtual().start(() -> {
            try {
                tx.executeWithoutResult(status -> {
                    backendPid.set(jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single());
                    reservations.confirmIfHeld(hold.id()).ifPresent(reservationEvents::confirmed);
                    updated.countDown();
                    await(die);                                   // the instance "hangs", then dies
                });
            } catch (Throwable t) {
                paymentFailure.set(t);
            }
        });
        await(updated);
        // The instance dies: its DB session is gone, PostgreSQL rolls the open transaction back.
        jdbc.sql("SELECT pg_terminate_backend(:pid)").param("pid", backendPid.get()).query(Boolean.class).single();
        die.countDown();
        payment.join();

        assertThat(paymentFailure.get()).as("the dying instance never committed").isNotNull();
        assertThat(statusOf(hold)).as("rolled back").isEqualTo(ReservationStatus.HELD);
        assertThat(confirmedEvents(hold)).isZero();

        ConfirmOutcome retry = service().confirm(hold.id());        // provider retries, another instance
        assertThat(retry).isInstanceOf(ConfirmOutcome.Confirmed.class);
        assertThat(statusOf(hold)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmedEvents(hold)).as("exactly one ReservationConfirmed").isEqualTo(1);
        invariant.assertConsistent(eventId);
    }

    @Test
    void instanceDiesAfterCommitThenTheRetryDoesNotConfirmTwice() {
        long eventId = events.create("pay-crash-after-commit", 1);
        Reservation hold = newHold(eventId);

        ConfirmOutcome first = service().confirm(hold.id());        // committed; the answer is "lost"
        ConfirmOutcome retry = service().confirm(hold.id());        // provider retries

        assertThat(first).isInstanceOf(ConfirmOutcome.Confirmed.class);
        assertThat(retry).as("replayed, not confirmed again").isInstanceOf(ConfirmOutcome.AlreadyConfirmed.class);
        assertThat(confirmedEvents(hold)).as("exactly one ReservationConfirmed").isEqualTo(1);
        invariant.assertConsistent(eventId);
    }

    private Reservation newHold(long eventId) {
        return ((ReservationOutcome.Created) service().reserve(eventId, "alice", "pay-" + eventId)).reservation();
    }

    private ReservationStatus statusOf(Reservation hold) {
        return reservations.findById(hold.id()).orElseThrow().status();
    }

    private long confirmedEvents(Reservation hold) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id AND event_type = :type")
                .param("id", hold.id()).param("type", ReservationEvents.CONFIRMED).query(Long.class).single();
    }

    private ReservationService service() {
        ReservationStrategy atomic = strategies.stream().filter(s -> s.name().equals("atomic")).findFirst().orElseThrow();
        return new ReservationService(atomic, reservations, events, reservationEvents, idempotency, tx);
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
