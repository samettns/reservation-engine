package io.github.samettns.reservation.domain;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Load shedding at the DB step ({@code reservation.load-shedding.mode=db}): limits only the
 * expensive part of a reservation (idempotency claim + stock transaction), AFTER the sold-out
 * shortcuts. The cheap answers ("sold out" from one Redis GET or one row read) are never refused.
 *
 * <p>Why here and not at the HTTP entry: by Little's law (in flight = throughput x latency) the
 * right limit depends on the work. The DB step is bounded by the connection pool and the hot row,
 * so its limit is about the pool size; the shortcuts handle thousands per second and need no limit.
 * One limit at the entry has to pick one of the two (measured in stage 7a: limit 20 at the entry
 * halved contention latency but slowed sellouts by 32-45%).
 */
@Component
public class DbStepLimiter {

    private final boolean active;
    private final int maxInFlight;
    private final Semaphore permits;
    private final Counter refused;

    @Autowired
    public DbStepLimiter(@Value("${reservation.load-shedding.enabled:true}") boolean enabled,
                         @Value("${reservation.load-shedding.mode:http}") String mode,
                         @Value("${reservation.load-shedding.max-in-flight:100}") int maxInFlight,
                         MeterRegistry meters) {
        this(enabled && "db".equals(mode), maxInFlight, meters);
    }

    DbStepLimiter(boolean active, int maxInFlight, MeterRegistry meters) {
        this.active = active;
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
        this.refused = Counter.builder("reservation.load_shedding.rejected").tag("at", "db-step").register(meters);
        if (active) {
            Gauge.builder("reservation.load_shedding.in_flight", this, l -> l.maxInFlight - l.permits.availablePermits())
                    .tag("at", "db-step").register(meters);
        }
    }

    /** No limit: for tests and for the other modes. */
    public static DbStepLimiter unlimited() {
        return new DbStepLimiter(false, 0, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** A limit of {@code n} in-flight DB steps (tests). */
    public static DbStepLimiter of(int n) {
        return new DbStepLimiter(true, n, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** @return true if the caller may run the DB step; it must then call {@link #exit()} exactly once */
    public boolean tryEnter() {
        if (!active) {
            return true;
        }
        if (permits.tryAcquire()) {
            return true;
        }
        refused.increment();
        return false;
    }

    public void exit() {
        if (active) {
            permits.release();
        }
    }

    public boolean active() {
        return active;
    }
}
