package io.github.samettns.reservation.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs the sweeper periodically on EVERY instance. No leader election is needed: the sweeper is
 * safe to run in parallel (see {@link HoldExpirySweeper}), and running it everywhere means expiry
 * keeps working when an instance dies. Disabled in tests that drive the sweeper by hand.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reservation.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    private final HoldExpirySweeper sweeper;

    public HoldExpiryScheduler(HoldExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.interval:PT1S}")
    void sweep() {
        try {
            int expired = sweeper.sweepOnce();
            if (expired > 0) {
                log.info("Expired {} holds and returned their stock", expired);
            }
        } catch (RuntimeException e) {
            // Keep the schedule alive; the next run retries whatever was not expired.
            log.warn("Hold expiry sweep failed", e);
        }
    }
}
