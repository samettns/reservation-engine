package io.github.samettns.reservation.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Example downstream consumer ("send a notification for every reservation event").
 *
 * <p><b>Idempotent:</b> delivery is at-least-once (relay retries, consumer rebalances), so the
 * same message can arrive more than once. The message id is inserted into {@code consumer_inbox}
 * in the SAME transaction as the effect; if it is already there, the message is a duplicate and
 * the effect is skipped. Effect and "seen" marker commit together, so a crash cannot leave one
 * without the other. The Kafka offset is committed after this method returns; if the process
 * dies before that, the message is delivered again and the inbox absorbs it.
 *
 * <p>It also checks ordering: messages of one reservation must arrive in outbox order. An older
 * message after a newer one is counted as out-of-order (tests require zero).
 */
@Component
public class NotificationConsumer {

    static final String CONSUMER = "notifications";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter outOfOrder;

    public NotificationConsumer(JdbcClient jdbc, TransactionTemplate tx, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.applied = Counter.builder("reservation.consumer.applied").register(meters);
        this.duplicates = Counter.builder("reservation.consumer.duplicates").register(meters);
        this.outOfOrder = Counter.builder("reservation.consumer.out_of_order").register(meters);
    }

    @KafkaListener(id = "notifications", topics = OutboxRelay.TOPIC, groupId = "reservation-notifications",
            autoStartup = "${reservation.events.consumer.enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record) {
        UUID messageId = UUID.fromString(header(record, OutboxRelay.HEADER_MESSAGE_ID));
        String eventType = header(record, OutboxRelay.HEADER_EVENT_TYPE);
        long outboxId = Long.parseLong(header(record, OutboxRelay.HEADER_OUTBOX_ID));
        UUID reservationId = UUID.fromString(record.key());

        tx.executeWithoutResult(status -> {
            int firstTime = jdbc.sql("""
                            INSERT INTO consumer_inbox (consumer, message_id) VALUES (:consumer, :messageId)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("consumer", CONSUMER)
                    .param("messageId", messageId)
                    .update();
            if (firstTime == 0) {
                duplicates.increment(); // seen before: effect already applied, skip
                return;
            }
            // COALESCE: max() over no rows is NULL, which JdbcClient.single() rejects. (Before this
            // fix the FIRST message of every reservation threw, was retried, and blocked its partition.)
            long latestSeen = jdbc.sql("SELECT COALESCE(max(outbox_id), -1) FROM notifications WHERE reservation_id = :id")
                    .param("id", reservationId)
                    .query(Long.class)
                    .single();
            if (latestSeen > outboxId) {
                outOfOrder.increment();
            }
            jdbc.sql("""
                            INSERT INTO notifications (message_id, reservation_id, event_type, outbox_id)
                            VALUES (:messageId, :reservationId, :eventType, :outboxId)
                            """)
                    .param("messageId", messageId)
                    .param("reservationId", reservationId)
                    .param("eventType", eventType)
                    .param("outboxId", outboxId)
                    .update();
            applied.increment();
        });
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header h = record.headers().lastHeader(name);
        if (h == null) {
            throw new IllegalArgumentException("missing header " + name);
        }
        return new String(h.value(), StandardCharsets.UTF_8);
    }
}
