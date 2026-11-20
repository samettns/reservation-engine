package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Load shedding at the DB step (mode=db): only the expensive part is limited. A full limiter
 * refuses a reservation BEFORE the idempotency claim (no side effect), but never the sold-out
 * shortcut, which is the whole point of limiting the DB step instead of the HTTP entry.
 */
class DbStepLimiterTest extends IntegrationTest {

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

    @Test
    void fullDbStepRefusesBeforeTheClaim() {
        long eventId = events.create("db-step-full", 5);
        ReservationService full = service(DbStepLimiter.of(0));

        assertThatThrownBy(() -> full.reserve(eventId, "alice", "dbs-" + eventId))
                .isInstanceOf(OverloadedException.class);
        assertThat(idempotency.find("alice", "dbs-" + eventId)).as("refused before the claim").isEmpty();
        assertThat(events.findStock(eventId).orElseThrow().remaining()).isEqualTo(5);

        assertThat(service(DbStepLimiter.unlimited()).reserve(eventId, "alice", "dbs-" + eventId))
                .as("same key afterwards: no side effect").isInstanceOf(ReservationOutcome.Created.class);
    }

    @Test
    void soldOutShortcutIsNeverRefused() {
        long eventId = events.create("db-step-soldout", 1);
        ReservationService unlimited = service(DbStepLimiter.unlimited());
        unlimited.reserve(eventId, "u1", "dbs-so-1-" + eventId);                      // takes the last unit
        assertThat(unlimited.reserve(eventId, "u2", "dbs-so-2-" + eventId))           // sets the sold-out marker
                .isInstanceOf(ReservationOutcome.SoldOut.class);

        ReservationOutcome answer = service(DbStepLimiter.of(0)).reserve(eventId, "u3", "dbs-so-3-" + eventId);

        assertThat(answer).as("answered by the shortcut although the DB step is full")
                .isInstanceOf(ReservationOutcome.SoldOut.class);
    }

    @Test
    void permitIsReturnedOnEveryOutcomeIncludingErrors() {
        long first = events.create("db-step-permit-1", 5);
        long second = events.create("db-step-permit-2", 5);
        DbStepLimiter one = DbStepLimiter.of(1);
        ReservationService service = service(one);

        service.reserve(first, "alice", "dbs-p-" + first);                            // Created
        assertThatThrownBy(() -> service.reserve(second, "alice", "dbs-p-" + first))   // key reused: 422
                .isInstanceOf(IdempotencyKeyReusedException.class);

        assertThat(one.tryEnter()).as("the single permit is free again").isTrue();
        one.exit();
    }

    private ReservationService service(DbStepLimiter limiter) {
        ReservationStrategy atomic = strategies.stream().filter(s -> s.name().equals("atomic")).findFirst().orElseThrow();
        return new ReservationService(atomic, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, ReservationService.DEFAULT_IDEMPOTENCY_LEASE,
                new SimpleMeterRegistry(), () -> { }, limiter);
    }
}
