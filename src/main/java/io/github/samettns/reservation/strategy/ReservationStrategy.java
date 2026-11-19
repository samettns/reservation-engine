package io.github.samettns.reservation.strategy;

import java.util.OptionalInt;

/**
 * How one unit of stock is taken from an event. This is the only part that differs
 * between the strategies we compare; everything around it (idempotency, persisting the
 * reservation, the HTTP API) is shared.
 *
 * <p>Contract: called inside the caller's database transaction. If that transaction
 * rolls back, the stock change must be undone too.
 */
public interface ReservationStrategy {

    /** Short name used in config and benchmark reports, e.g. {@code atomic}. */
    String name();

    /**
     * Tries to take one unit of stock.
     *
     * @return the shard number the unit was taken from (stored on the reservation so an
     *         expired hold gives the unit back to the same shard), or empty if sold out
     * @throws ContentionException if the strategy gave up because of too much contention;
     *                             stock may still be available and the client may retry
     */
    OptionalInt tryAcquireOne(long eventId);
}
