package io.github.samettns.reservation.strategy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong();
    private final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(2), now::get);

    @Test
    void opensAfterThresholdFailuresInARowAndThenFailsFast() {
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.tryAcquirePermission()).as("2 failures: still closed").isTrue();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquirePermission()).as("open: callers skip the dependency").isFalse();
    }

    @Test
    void aSuccessResetsTheCountOfFailuresInARow() {
        breaker.onFailure();
        breaker.onFailure();
        breaker.onSuccess();
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void afterOpenDurationExactlyOneProbeIsLetThrough() {
        IntStream.range(0, 3).forEach(i -> breaker.onFailure());
        now.addAndGet(Duration.ofMillis(1999).toNanos());
        assertThat(breaker.tryAcquirePermission()).as("still open").isFalse();
        now.addAndGet(Duration.ofMillis(1).toNanos());

        assertThat(breaker.tryAcquirePermission()).as("the probe").isTrue();
        assertThat(breaker.tryAcquirePermission()).as("only one probe at a time").isFalse();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void probeSuccessClosesAndReportsTheRecovery() {
        IntStream.range(0, 3).forEach(i -> breaker.onFailure());
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        breaker.tryAcquirePermission();

        assertThat(breaker.onSuccess()).as("this success ended an outage").isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.onSuccess()).as("an ordinary success").isFalse();
    }

    @Test
    void probeFailureOpensAgainForAnotherFullPeriod() {
        IntStream.range(0, 3).forEach(i -> breaker.onFailure());
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        breaker.tryAcquirePermission();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }
}
