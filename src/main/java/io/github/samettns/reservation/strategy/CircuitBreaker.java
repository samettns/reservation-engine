package io.github.samettns.reservation.strategy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A minimal circuit breaker around a dependency (here: Redis).
 *
 * <pre>
 *   CLOSED --(failureThreshold failures in a row)--> OPEN --(openDuration passed)--> HALF_OPEN
 *     ^                                               ^                                 |
 *     +------------------- probe succeeds ------------+-------- probe fails ------------+
 * </pre>
 *
 * While OPEN, callers skip the dependency at once instead of each waiting for its timeout. After
 * {@code openDuration} exactly ONE caller is let through as a probe (HALF_OPEN); its result decides.
 *
 * <p>Time comes from a MONOTONIC clock (System.nanoTime by default): the wall clock can step
 * backwards (measured in this project under WSL2), which would keep a breaker open too long.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openNanos;
    private final LongSupplier nanoClock;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openedAt;

    public CircuitBreaker(int failureThreshold, Duration openDuration) {
        this(failureThreshold, openDuration, System::nanoTime);
    }

    CircuitBreaker(int failureThreshold, Duration openDuration, LongSupplier nanoClock) {
        this.failureThreshold = failureThreshold;
        this.openNanos = openDuration.toNanos();
        this.nanoClock = nanoClock;
    }

    /** @return true if the caller may call the dependency now */
    public boolean tryAcquirePermission() {
        return switch (state.get()) {
            case CLOSED -> true;
            case OPEN -> nanoClock.getAsLong() - openedAt >= openNanos
                    && state.compareAndSet(State.OPEN, State.HALF_OPEN);   // exactly one probe wins
            case HALF_OPEN -> false;                                       // the probe is in flight
        };
    }

    /** @return true if this success ended an outage (the circuit was not closed before) */
    public boolean onSuccess() {
        consecutiveFailures.set(0);
        return state.getAndSet(State.CLOSED) != State.CLOSED;
    }

    public void onFailure() {
        if (state.get() == State.HALF_OPEN || consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt = nanoClock.getAsLong();
            state.set(State.OPEN);
        }
    }

    public State state() {
        return state.get();
    }
}
