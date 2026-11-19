package io.github.samettns.reservation.strategy;

/**
 * Notified when units go back to an event's stock in the database (expired holds), inside the
 * transaction that returns them. Components that keep their own view of the stock (the Redis
 * gate counter) use it to stay in sync.
 */
public interface StockReturnListener {

    void onStockReturned(long eventId, int units);
}
