package io.github.samettns.reservation.api;

import io.github.samettns.reservation.domain.EventNotFoundException;
import io.github.samettns.reservation.domain.IdempotencyKeyReusedException;
import io.github.samettns.reservation.strategy.ContentionException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to RFC 9457 problem responses. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EventNotFoundException.class)
    ProblemDetail eventNotFound(EventNotFoundException e) {
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
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage()));
    }
}
