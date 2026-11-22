package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sold-out shortcut switch must not change correctness, only cost:
 * soldout-shortcuts.enabled=false gives correct answers from the DB and writes no sold-out marker,
 * in both transaction orders. (tx-order=stock-last, the default since stage 8, has its own
 * {@link StockLastTransactionTest}.)
 */
class BenchmarkSwitchesTest extends IntegrationTest {

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
    StockInvariant invariant;

    @ParameterizedTest(name = "stock-last {0}")
    @ValueSource(booleans = {true, false})
    void withoutShortcutsTheAnswersStayCorrectAndNoMarkerIsWritten(boolean stockLast) {
        long eventId = events.create("no-shortcuts", 1);
        ReservationService service = service("atomic", false, stockLast);

        assertThat(service.reserve(eventId, "u1", "ns-1-" + eventId)).isInstanceOf(ReservationOutcome.Created.class);
        assertThat(service.reserve(eventId, "u2", "ns-2-" + eventId)).isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(service.reserve(eventId, "u3", "ns-3-" + eventId)).isInstanceOf(ReservationOutcome.SoldOut.class);

        assertThat(events.findAvailability(eventId).orElseThrow().soldOut()).as("no marker written").isFalse();
        invariant.assertHolds(eventId, 1);
    }

    private ReservationService service(String name, boolean shortcuts, boolean stockLast) {
        ReservationStrategy strategy = strategies.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
        return new ReservationService(strategy, reservations, events, reservationEvents, idempotency, tx,
                ReservationService.DEFAULT_HOLD_TTL, ReservationService.DEFAULT_IDEMPOTENCY_LEASE,
                new SimpleMeterRegistry(), () -> { }, DbStepLimiter.unlimited(), shortcuts, stockLast);
    }
}
