package io.github.samettns.reservation.strategy;

/**
 * The strategy could not take a unit because other requests kept winning the race,
 * NOT because the event is sold out. Mapped to 503 + Retry-After so clients know a
 * retry may succeed (unlike 409 sold out, which is final).
 */
public class ContentionException extends RuntimeException {

    public ContentionException(long eventId, String reason) {
        super("Gave up on event " + eventId + ": " + reason);
    }
}
