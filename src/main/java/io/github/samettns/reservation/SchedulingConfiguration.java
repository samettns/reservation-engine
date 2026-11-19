package io.github.samettns.reservation;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on @Scheduled processing once, independent of which background jobs (hold expiry,
 * outbox relay) are enabled by configuration.
 */
@Configuration
@EnableScheduling
public class SchedulingConfiguration {
}
