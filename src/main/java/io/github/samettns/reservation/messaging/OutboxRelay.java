package io.github.samettns.reservation.messaging;

import io.github.samettns.reservation.infra.OutboxRepository;
import io.github.samettns.reservation.infra.OutboxRepository.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Polling outbox relay: claims unpublished outbox rows, sends them to Kafka and marks them
 * published, all inside one database transaction. Runs on every app instance.
 *
 * <p><b>Delivery is at-least-once, on purpose.</b> The Kafka sends and the "published" UPDATE
 * cannot be one atomic operation (two different systems). If the relay crashes or a send fails
 * after some messages reached Kafka but before the commit, the rows stay unpublished and are sent
 * again later. The alternative order (mark first, send after) would turn that into message LOSS.
 * Duplicates are therefore expected and handled by consumers (message-id deduplication).
 *
 * <p>Kafka key = reservation id, so all events of a reservation go to the same partition, in the
 * order they were sent. {@link OutboxRepository#claimBatch} makes sure they are also SENT in
 * order across parallel relays.
 */
@Component
public class OutboxRelay {

    public static final String TOPIC = "reservation-events";
    public static final String HEADER_MESSAGE_ID = "message-id";
    public static final String HEADER_EVENT_TYPE = "event-type";
    public static final String HEADER_OUTBOX_ID = "outbox-id";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Counter published;

    @Autowired
    public OutboxRelay(OutboxRepository outbox,
                       KafkaTemplate<String, String> kafka,
                       TransactionTemplate tx,
                       @Value("${reservation.events.relay.batch-size:100}") int batchSize,
                       @Value("${reservation.events.relay.send-timeout:PT10S}") Duration sendTimeout,
                       MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.tx = tx;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.published = Counter.builder("reservation.outbox.published").register(meters);
    }

    /**
     * Publishes everything that is currently publishable, batch by batch, until a batch comes back
     * empty. "Not full" is NOT the end: a batch only takes the oldest pending event of each
     * reservation, so a reservation's next event becomes claimable only after this batch commits.
     */
    public int publishPending() {
        int total = 0;
        int sent;
        while ((sent = publishBatch()) > 0) {
            total += sent;
        }
        return total;
    }

    /** One transaction: claim, send (and wait for the broker's ack), mark published, commit. */
    public int publishBatch() {
        Integer sent = tx.execute(status -> {
            List<OutboxMessage> batch = outbox.claimBatch(batchSize);
            if (batch.isEmpty()) {
                return 0;
            }
            // Sends are issued in outbox order from one thread; with an idempotent producer Kafka
            // keeps that order within each partition (= within each reservation).
            List<CompletableFuture<SendResult<String, String>>> acks = batch.stream()
                    .map(m -> kafka.send(toRecord(m)))
                    .toList();
            awaitAll(acks); // throws -> rollback -> rows stay unpublished -> retried (at-least-once)
            outbox.markPublished(batch.stream().map(OutboxMessage::id).toList());
            return batch.size();
        });
        published.increment(sent);
        return sent;
    }

    private static ProducerRecord<String, String> toRecord(OutboxMessage m) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, m.aggregateId().toString(), m.payload());
        record.headers()
                .add(HEADER_MESSAGE_ID, m.messageId().toString().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_EVENT_TYPE, m.eventType().getBytes(StandardCharsets.UTF_8))
                .add(HEADER_OUTBOX_ID, Long.toString(m.id()).getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void awaitAll(List<CompletableFuture<SendResult<String, String>>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while publishing", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Kafka did not acknowledge the batch; it will be retried", e);
        }
    }
}
