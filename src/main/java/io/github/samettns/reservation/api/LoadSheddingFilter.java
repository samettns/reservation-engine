package io.github.samettns.reservation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/**
 * Load shedding at the HTTP entry ({@code reservation.load-shedding.mode=http}): at most
 * {@code max-in-flight} reservation requests per instance are being worked on at any moment; the
 * next one is refused at once with 503 + Retry-After instead of queueing. (The other mode,
 * {@code db}, limits only the DB step after the sold-out shortcuts: see DbStepLimiter.)
 *
 * <p>Why: with virtual threads every request gets a thread, but only {@code pool-size} of them can
 * hold a DB connection. Without a limit, thousands queue for a connection, each waits up to the
 * pool timeout, and the ones that get through are slow too (p99 of the SUCCESSFUL requests grows
 * with the queue). Refusing early keeps the queue short, so admitted requests stay fast and
 * refused clients know immediately that a retry later can work.
 *
 * <p>Only {@code POST /events/{id}/reserve} is limited. Payment confirmations are never shed: a
 * delayed confirmation can make a paid hold expire.
 *
 * <p>Refusing here is side-effect free: it happens before the idempotency claim, so a retry with
 * the same key is processed normally.
 */
@Component
@ConditionalOnExpression("${reservation.load-shedding.enabled:true} and '${reservation.load-shedding.mode:http}' == 'http'")
public class LoadSheddingFilter extends OncePerRequestFilter {

    static final URI OVERLOADED_TYPE = ApiExceptionHandler.OVERLOADED_TYPE;
    private static final Pattern RESERVE = Pattern.compile("^/events/\\d+/reserve$");

    private final Semaphore permits;
    private final int maxInFlight;
    private final Counter shed;
    private final ObjectMapper json;
    private final RetryAfter retryAfter;

    public LoadSheddingFilter(@Value("${reservation.load-shedding.max-in-flight:100}") int maxInFlight,
                              MeterRegistry meters, ObjectMapper json, RetryAfter retryAfter) {
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
        this.json = json;
        this.retryAfter = retryAfter;
        this.shed = Counter.builder("reservation.load_shedding.rejected").tag("at", "http").register(meters);
        Gauge.builder("reservation.load_shedding.in_flight", this, f -> f.maxInFlight - f.permits.availablePermits())
                .tag("at", "http").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && RESERVE.matcher(request.getRequestURI()).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!permits.tryAcquire()) {
            shed.increment();
            refuse(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    private void refuse(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Too many reservation requests in progress on this instance; retry later");
        problem.setType(OVERLOADED_TYPE);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfter.next());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }

    // --- test support: occupy / free slots as if requests were in flight ----

    boolean occupy() {
        return permits.tryAcquire();
    }

    void free() {
        permits.release();
    }
}
