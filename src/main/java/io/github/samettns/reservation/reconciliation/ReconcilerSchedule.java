package io.github.samettns.reservation.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Every instance tries each round; the lock lets one of them work. Disabled in tests and benchmarks. */
@Configuration
@ConditionalOnProperty(name = "reservation.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class ReconcilerSchedule {

    private static final Logger log = LoggerFactory.getLogger(ReconcilerSchedule.class);

    private final RedisStockReconciler reconciler;

    public ReconcilerSchedule(RedisStockReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${reservation.reconciler.interval:PT30S}")
    void run() {
        try {
            reconciler.reconcileOnce();
        } catch (RuntimeException e) {
            log.warn("Redis/DB reconciliation failed: {}", e.getMessage());
        }
    }
}
