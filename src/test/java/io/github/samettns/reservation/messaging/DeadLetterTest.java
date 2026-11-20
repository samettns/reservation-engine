package io.github.samettns.reservation.messaging;

import com.zaxxer.hikari.HikariDataSource;
import io.github.samettns.reservation.FailFastIntegrationTest;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Poison messages. Stage 5a had one by accident: a query returned NULL for the first message of
 * every reservation, the handler threw, the message was redelivered again and again, and every
 * later message of its partition waited behind it. This test recreates that situation on purpose
 * (a message the DB step deterministically rejects) and checks that it is retried a bounded
 * number of times, parked in the dead-letter topic with the reason, and that the messages BEHIND
 * it in the same partition are still processed.
 */
class DeadLetterTest extends FailFastIntegrationTest {

    private static final String POISON = "POISON";
    private static final String CHECK = "ck_test_no_poison";

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MeterRegistry meters;
    @Autowired
    HikariDataSource dataSource;

    @Test
    void poisonMessageIsDeadLetteredAfterThreeAttemptsAndThePartitionKeepsMoving() {
        // A deterministic failure in the DB step for exactly one message, like the NULL bug of 5a.
        jdbc.sql("ALTER TABLE notifications ADD CONSTRAINT " + CHECK + " CHECK (event_type <> '" + POISON + "')").update();
        try {
            UUID reservation = UUID.randomUUID();     // one key -> one partition, in this order:
            UUID before = send(reservation, ReservationEvents.HELD, 1);
            double failuresBefore = counter("reservation.consumer.failures");
            UUID poison = send(reservation, POISON, 2);
            UUID after = send(reservation, ReservationEvents.CONFIRMED, 3);

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(notifiedMessages(reservation)).as("the message behind the poison was processed")
                            .containsExactly(before, after));

            ConsumerRecord<String, String> dead = awaitDeadLetter(poison);
            assertThat(counter("reservation.consumer.failures") - failuresBefore)
                    .as("delivered max-attempts times, then given up").isEqualTo(3);
            assertThat(dead.key()).isEqualTo(reservation.toString());
            assertThat(header(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(OutboxRelay.TOPIC);
            assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains(CHECK);
            assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).as("the real failure, not the listener wrapper")
                    .isEqualTo(org.springframework.dao.DataIntegrityViolationException.class.getName());
            assertThat(header(dead, OutboxRelay.HEADER_EVENT_TYPE)).as("original headers kept").isEqualTo(POISON);
        } finally {
            jdbc.sql("ALTER TABLE notifications DROP CONSTRAINT IF EXISTS " + CHECK).update();
        }
    }

    /**
     * The real exception of the 5a bug (the original query, without COALESCE, for a reservation with
     * no notifications yet) is classified as a poison message, not as an outage: it would be
     * retried 3 times and dead-lettered, not retried forever.
     */
    @Test
    void theStage5aNullBugIsTreatedAsAPoisonMessage() {
        Throwable bug = catchThrowable(() -> jdbc.sql("SELECT max(outbox_id) FROM notifications WHERE reservation_id = :id")
                .param("id", UUID.randomUUID())
                .query(Long.class)
                .single());

        assertThat(bug).as("the original 5a query still throws on NULL").isNotNull();
        assertThat(MessagingConfiguration.isOutage(bug)).isFalse();
    }

    /** Malformed content can never succeed: dead-lettered on the first failure, no retries. */
    @Test
    void malformedMessageIsDeadLetteredWithoutRetrying() {
        UUID reservation = UUID.randomUUID();
        double failuresBefore = counter("reservation.consumer.failures");
        ProducerRecord<String, String> malformed = record(reservation, ReservationEvents.HELD, "not-a-number");
        UUID messageId = UUID.fromString(header(malformed.headers().lastHeader(OutboxRelay.HEADER_MESSAGE_ID)));
        sendRecord(malformed);
        UUID after = send(reservation, ReservationEvents.CONFIRMED, 2);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(notifiedMessages(reservation)).containsExactly(after));
        ConsumerRecord<String, String> dead = awaitDeadLetter(messageId);
        assertThat(header(dead, KafkaHeaders.DLT_EXCEPTION_FQCN)).isEqualTo(NumberFormatException.class.getName());
        assertThat(counter("reservation.consumer.failures") - failuresBefore).isEqualTo(1);
    }

    /**
     * The opposite case: the DB is unreachable (here: no connection can be had). That is not the
     * message's fault, every message would fail the same way, so dead-lettering would just move the
     * whole topic into the DLT. It is retried past max-attempts until the outage ends, then applied.
     */
    @Test
    void outageIsRetriedUntilItEndsInsteadOfDeadLettered() throws Exception {
        UUID reservation = UUID.randomUUID();
        double failuresBefore = counter("reservation.consumer.failures");
        List<Connection> held = holdAllConnections();
        UUID message;
        try { // each delivery attempt now fails after the pool timeout (500 ms)
            message = send(reservation, ReservationEvents.HELD, 1);
            await().atMost(Duration.ofSeconds(20)).until(() ->
                    counter("reservation.consumer.failures") - failuresBefore > 3); // beyond max-attempts
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(notifiedMessages(reservation)).containsExactly(message));
        assertThat(deadLetters(message, Duration.ofSeconds(2))).as("not dead-lettered").isEmpty();
    }

    // --- helpers -------------------------------------------------------------

    private List<Connection> holdAllConnections() throws SQLException {
        List<Connection> held = new ArrayList<>();
        while (held.size() < dataSource.getMaximumPoolSize()) {
            held.add(dataSource.getConnection());
        }
        return held;
    }

    private List<ConsumerRecord<String, String>> deadLetters(UUID messageId, Duration quiet) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-probe-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(MessagingConfiguration.DEAD_LETTER_TOPIC));
            long end = System.nanoTime() + quiet.toNanos();
            while (System.nanoTime() < end) {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (messageId.toString().equals(header(r, OutboxRelay.HEADER_MESSAGE_ID))) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    private UUID send(UUID reservation, String eventType, long outboxId) {
        ProducerRecord<String, String> r = record(reservation, eventType, Long.toString(outboxId));
        sendRecord(r);
        return UUID.fromString(header(r.headers().lastHeader(OutboxRelay.HEADER_MESSAGE_ID)));
    }

    private static ProducerRecord<String, String> record(UUID reservation, String eventType, String outboxId) {
        ProducerRecord<String, String> r = new ProducerRecord<>(OutboxRelay.TOPIC, reservation.toString(), "{}");
        r.headers()
                .add(OutboxRelay.HEADER_MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .add(OutboxRelay.HEADER_EVENT_TYPE, eventType.getBytes(StandardCharsets.UTF_8))
                .add(OutboxRelay.HEADER_OUTBOX_ID, outboxId.getBytes(StandardCharsets.UTF_8));
        return r;
    }

    private void sendRecord(ProducerRecord<String, String> r) {
        try {
            kafka.send(r).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<UUID> notifiedMessages(UUID reservation) {
        return jdbc.sql("SELECT message_id FROM notifications WHERE reservation_id = :id ORDER BY id")
                .param("id", reservation).query(UUID.class).list();
    }

    /** Reads the dead-letter topic with a fresh group until the given message shows up. */
    private ConsumerRecord<String, String> awaitDeadLetter(UUID messageId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("dlt-probe-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(MessagingConfiguration.DEAD_LETTER_TOPIC));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(250)).forEach(r -> {
                    if (messageId.toString().equals(header(r, OutboxRelay.HEADER_MESSAGE_ID))) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
        }
        assertThat(found).as("exactly one dead letter per poison message").hasSize(1);
        return found.getFirst();
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return header(r.headers().lastHeader(name));
    }

    private static String header(Header h) {
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }
}
