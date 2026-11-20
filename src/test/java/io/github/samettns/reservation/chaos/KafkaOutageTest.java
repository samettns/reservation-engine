package io.github.samettns.reservation.chaos;

import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.messaging.OutboxRelay;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.support.StockInvariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Kafka becomes unreachable (the proxy refuses every connection).
 *
 * <p>Behaviour: reservations are NEITHER refused NOR delayed: Kafka is not on the request path. The
 * event of each reservation is in the outbox, committed with it. The relay fails within seconds
 * (producer max.block.ms 5 s, not the default 60 s) and leaves the rows unpublished; once Kafka is
 * back, the relay publishes them and the consumer applies each exactly once.
 */
class KafkaOutageTest extends ChaosIntegrationTest {

    private static final int HOLDS = 10;

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
    OutboxRelay relay;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StockInvariant invariant;

    @Test
    void reservationsContinueAndEventsWaitInTheOutboxUntilKafkaIsBack() throws Exception {
        long eventId = events.create("kafka-down", HOLDS);
        ReservationService service = atomicService();
        KAFKA_PROXY.disable();

        long start = System.nanoTime();
        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < HOLDS; i++) {
            ReservationOutcome o = service.reserve(eventId, "u" + i, "ko-" + eventId + "-" + i);
            assertThat(o).as("Kafka is not on the request path").isInstanceOf(ReservationOutcome.Created.class);
            held.add(((ReservationOutcome.Created) o).reservation().id());
        }
        long reserveMs = (System.nanoTime() - start) / 1_000_000;

        long relayStart = System.nanoTime();
        Throwable relayFailure = null;
        try {
            relay.publishPending();
        } catch (RuntimeException e) {
            relayFailure = e;
        }
        long relayMs = (System.nanoTime() - relayStart) / 1_000_000;
        System.out.printf("[kafka down] %d reservations in %d ms; relay gave up after %d ms (%s)%n",
                HOLDS, reserveMs, relayMs, relayFailure == null ? "no exception" : relayFailure.getClass().getSimpleName());
        assertThat(relayFailure).as("the relay cannot publish").isNotNull();
        assertThat(relayMs).as("fails within seconds, not after the 60 s default").isLessThan(20_000);
        assertThat(unpublished(held)).as("events wait in the outbox").isEqualTo(HOLDS);
        invariant.assertHolds(eventId, HOLDS);

        KAFKA_PROXY.enable();
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .until(() -> {
                    relay.publishPending();
                    return unpublished(held) == 0;
                });
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(notifications(held)).as("each event applied exactly once").isEqualTo(HOLDS));
        Thread.sleep(2_000);                                              // late duplicates would show up now
        assertThat(notifications(held)).isEqualTo(HOLDS);
    }

    private long unpublished(List<UUID> ids) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE aggregate_id = ANY(:ids) AND published_at IS NULL")
                .param("ids", ids.toArray(UUID[]::new)).query(Long.class).single();
    }

    private long notifications(List<UUID> ids) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE reservation_id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new)).query(Long.class).single();
    }

    private ReservationService atomicService() {
        ReservationStrategy atomic = strategies.stream().filter(s -> s.name().equals("atomic")).findFirst().orElseThrow();
        return new ReservationService(atomic, reservations, events, reservationEvents, idempotency, tx);
    }
}
