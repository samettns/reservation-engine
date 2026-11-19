package io.github.samettns.reservation.domain;

/**
 * This request's idempotency claim was taken over (its lease expired while it was slow). Thrown
 * inside the reservation transaction so that everything it did is rolled back.
 */
class StaleClaimException extends RuntimeException {

    StaleClaimException(String idempotencyKey, long fenceToken) {
        super("Idempotency claim for key '" + idempotencyKey + "' (token " + fenceToken + ") was taken over");
    }
}
