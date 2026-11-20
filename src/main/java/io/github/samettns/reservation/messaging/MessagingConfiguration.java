package io.github.samettns.reservation.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

@Configuration
public class MessagingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingConfiguration.class);

    public static final String DEAD_LETTER_TOPIC = OutboxRelay.TOPIC + ".DLT";

    /**
     * 6 partitions: up to 6 consumer instances can share the work. All events of one reservation
     * land in one partition (key = reservation id), which is what keeps them in order.
     */
    @Bean
    NewTopic reservationEventsTopic() {
        return TopicBuilder.name(OutboxRelay.TOPIC).partitions(6).replicas(1).build();
    }

    /** Same partition count, so a dead letter keeps the partition (and thus the key) of its original. */
    @Bean
    NewTopic reservationEventsDeadLetterTopic() {
        return TopicBuilder.name(DEAD_LETTER_TOPIC).partitions(6).replicas(1).build();
    }

    /**
     * What the consumer does when handling a message throws. Spring Boot plugs this bean into the
     * listener container factory.
     *
     * <ul>
     *   <li><b>The message itself is the problem</b> (any other exception: a bug, a constraint
     *       violation, malformed content): retrying will not help. After {@code max-attempts} tries
     *       it goes to the dead-letter topic with the exception in its headers, the offset is
     *       committed, and the partition moves on. Malformed headers
     *       ({@link IllegalArgumentException}: missing header, bad UUID or number) are not retried
     *       at all.</li>
     *   <li><b>The environment is the problem</b> (DB/connection unavailable): every message would
     *       fail the same way, and dead-lettering them all would empty the topic into the DLT.
     *       Retried with exponential backoff, without limit: the partition waits until the DB is
     *       back. That blocking is intended: there is nothing to process without the DB.</li>
     * </ul>
     *
     * <p>Trade-off: a dead-lettered message breaks per-reservation ordering for that reservation
     * (later events are processed without it). The alternative, blocking the partition forever, stops
     * every reservation that hashes to it.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, MeterRegistry meters,
                                          @Value("${reservation.events.consumer.max-attempts:3}") int maxAttempts,
                                          @Value("${reservation.events.consumer.retry-backoff:PT0.2S}") Duration backoff) {
        Counter deadLettered = Counter.builder("reservation.consumer.dead_lettered").register(meters);
        DeadLetterPublishingRecoverer toDeadLetterTopic = new DeadLetterPublishingRecoverer(kafka,
                (record, ex) -> new TopicPartition(DEAD_LETTER_TOPIC, record.partition()));
        FixedBackOff poisonBackOff = new FixedBackOff(backoff.toMillis(), maxAttempts - 1L);
        ExponentialBackOff outageBackOff = new ExponentialBackOff(backoff.toMillis(), 2.0);
        outageBackOff.setMaxInterval(Duration.ofSeconds(5).toMillis()); // no max elapsed time: retry until it works

        DefaultErrorHandler handler = new DefaultErrorHandler((record, ex) -> {
            deadLettered.increment();
            log.error("Dead-lettering {}-{}@{} (key {}) after repeated failures: {}", record.topic(),
                    record.partition(), record.offset(), record.key(), rootMessage(ex));
            // The DLT headers should name the real failure, not the listener wrapper around it.
            Exception reason = ex instanceof ListenerExecutionFailedException && ex.getCause() instanceof Exception cause
                    ? cause : ex;
            toDeadLetterTopic.accept(record, reason);
        }, poisonBackOff);
        handler.setBackOffFunction((record, ex) -> isOutage(ex) ? outageBackOff : poisonBackOff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }

    static boolean isOutage(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DataAccessResourceFailureException
                    || t instanceof TransientDataAccessException
                    || t instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /** Runs the relay on every instance; parallel relays are safe (SKIP LOCKED + per-reservation order). */
    @Configuration
    @ConditionalOnProperty(name = "reservation.events.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelaySchedule {

        private static final Logger log = LoggerFactory.getLogger(RelaySchedule.class);

        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${reservation.events.relay.interval:PT0.2S}")
        void publish() {
            try {
                relay.publishPending();
            } catch (RuntimeException e) {
                // Rows stay unpublished and are retried on the next run.
                log.warn("Outbox relay run failed: {}", e.getMessage());
            }
        }
    }
}
