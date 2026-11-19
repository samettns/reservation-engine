package io.github.samettns.reservation.domain;

/**
 * The client sent an idempotency key it already used, but for a different event.
 * Replaying the old result would be wrong and silently creating a new one would
 * break the key's meaning, so the request is rejected.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey, long originalEventId) {
        super("Idempotency key '" + idempotencyKey + "' was already used for event " + originalEventId);
    }
}
