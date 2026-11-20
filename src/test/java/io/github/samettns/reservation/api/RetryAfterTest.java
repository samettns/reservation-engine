package io.github.samettns.reservation.api;

import io.github.samettns.reservation.domain.OverloadedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    @Test
    void jitterCoversTheWholeRangeAndNothingElse() {
        RetryAfter retryAfter = new RetryAfter(1, 3);
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> seen.add(retryAfter.next()));
        assertThat(seen).containsExactlyInAnyOrder("1", "2", "3");
    }

    @Test
    void minEqualsMaxTurnsJitterOff() {
        RetryAfter retryAfter = new RetryAfter(1, 1);
        assertThat(IntStream.range(0, 100).mapToObj(i -> retryAfter.next()).distinct()).containsExactly("1");
    }

    @Test
    void dbStepRefusalIsA503WithJitteredRetryAfter() {
        var handler = new ApiExceptionHandler(new SimpleMeterRegistry(), new RetryAfter(1, 3));
        var response = handler.overloaded(new OverloadedException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isIn("1", "2", "3");
        assertThat(response.getBody().getType()).isEqualTo(ApiExceptionHandler.OVERLOADED_TYPE);
    }
}
