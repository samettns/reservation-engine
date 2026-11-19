package io.github.samettns.reservation.strategy;

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
     * @return {@code true} if a unit was taken, {@code false} if the event is sold out
     */
    boolean tryAcquireOne(long eventId);
}
