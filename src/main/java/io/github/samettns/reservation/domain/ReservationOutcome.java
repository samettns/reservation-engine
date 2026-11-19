package io.github.samettns.reservation.domain;

/**
 * Every possible result of a reservation attempt. "Sold out" is an expected
 * business result, not an error, so it is modelled as a value instead of an exception.
 * The sealed interface lets the controller handle all cases with an exhaustive switch.
 */
public sealed interface ReservationOutcome {

    /** A new reservation was created by this request. */
    record Created(Reservation reservation) implements ReservationOutcome {
    }

    /** The same (user, idempotency key) was seen before; the original result is returned. */
    record Replayed(Reservation reservation) implements ReservationOutcome {
    }

    /** No stock left. */
    record SoldOut(long eventId) implements ReservationOutcome {
    }
}
