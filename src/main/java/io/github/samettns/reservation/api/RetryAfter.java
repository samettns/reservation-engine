package io.github.samettns.reservation.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The Retry-After value of every 503, with jitter: a random whole number of seconds in
 * [min, max]. If every refused client is told "1", all clients refused in the same moment come
 * back in the same moment (thundering herd) and are refused together again. Spreading the value
 * spreads their return. Whole seconds because the header only allows delay-seconds (RFC 9110).
 * min = max turns jitter off.
 */
@Component
public class RetryAfter {

    private final int minSeconds;
    private final int maxSeconds;

    public RetryAfter(@Value("${reservation.retry-after.min-seconds:1}") int minSeconds,
                      @Value("${reservation.retry-after.max-seconds:3}") int maxSeconds) {
        if (minSeconds < 0 || maxSeconds < minSeconds) {
            throw new IllegalArgumentException("Retry-After range [" + minSeconds + ", " + maxSeconds + "]");
        }
        this.minSeconds = minSeconds;
        this.maxSeconds = maxSeconds;
    }

    public String next() {
        int seconds = minSeconds == maxSeconds ? minSeconds
                : ThreadLocalRandom.current().nextInt(minSeconds, maxSeconds + 1);
        return Integer.toString(seconds);
    }

    public int minSeconds() {
        return minSeconds;
    }

    public int maxSeconds() {
        return maxSeconds;
    }
}
