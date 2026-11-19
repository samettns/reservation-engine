package io.github.samettns.reservation.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
public class MessagingConfiguration {

    /**
     * 6 partitions: up to 6 consumer instances can share the work. All events of one reservation
     * land in one partition (key = reservation id), which is what keeps them in order.
     */
    @Bean
    NewTopic reservationEventsTopic() {
        return TopicBuilder.name(OutboxRelay.TOPIC).partitions(6).replicas(1).build();
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
