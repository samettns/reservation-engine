package io.github.samettns.reservation.api;

import io.github.samettns.reservation.domain.EventNotFoundException;
import io.github.samettns.reservation.domain.IdempotencyKeyReusedException;
import io.github.samettns.reservation.domain.OverloadedException;
import io.github.samettns.reservation.domain.ReservationNotFoundException;
import io.github.samettns.reservation.strategy.ContentionException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/** Maps domain exceptions to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final URI DEPENDENCY_UNAVAILABLE_TYPE = URI.create("urn:problem-type:dependency-unavailable");
    static final URI OVERLOADED_TYPE = URI.create("urn:problem-type:overloaded");

    private final Counter unavailable;
    private final RetryAfter retryAfter;

    public ApiExceptionHandler(MeterRegistry meters, RetryAfter retryAfter) {
        this.unavailable = Counter.builder("reservation.api.dependency_unavailable").register(meters);
        this.retryAfter = retryAfter;
    }

    /** Load shedding at the DB step (mode=db): refused before the claim, so retrying is safe. */
    @ExceptionHandler(OverloadedException.class)
    ResponseEntity<ProblemDetail> overloaded(OverloadedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        problem.setType(OVERLOADED_TYPE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter.next())
                .body(problem);
    }

    @ExceptionHandler({EventNotFoundException.class, ReservationNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** 422, as recommended by the IETF Idempotency-Key header draft for a key reused with a different payload. */
    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    /**
     * 503 + Retry-After instead of 409: stock may still exist, the request just lost too
     * many races. 409 (sold out) tells the client to stop; 503 tells it a retry can work.
     */
    @ExceptionHandler(ContentionException.class)
    ResponseEntity<ProblemDetail> contention(ContentionException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter.next())
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }

    /**
     * A dependency is temporarily unreachable: no DB connection within the pool timeout (pool
     * exhausted or DB down), or Redis unreachable. That is a temporary state of the server, not a
     * bug: 503 + Retry-After, not 500. Covers both ways Spring reports it: from a plain query
     * ({@link DataAccessResourceFailureException}: CannotGetJdbcConnectionException,
     * RedisConnectionFailureException) and from opening a transaction
     * ({@link CannotCreateTransactionException}).
     *
     * <p>Safe to retry with the same Idempotency-Key: either nothing happened, or the claim was
     * released; if even the release could not get a connection, the claim's lease expires and a
     * retry sees "in progress" until then.
     */
    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> dependencyUnavailable(RuntimeException e) {
        unavailable.increment();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "A backing service is temporarily unavailable; retry later");
        problem.setType(DEPENDENCY_UNAVAILABLE_TYPE);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter.next())
                .body(problem);
    }
}
