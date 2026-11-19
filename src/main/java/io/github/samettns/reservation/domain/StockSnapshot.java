package io.github.samettns.reservation.domain;

/**
 * Stock numbers of one event, read in a single SQL statement so that all three
 * values come from the same database snapshot.
 */
public record StockSnapshot(long eventId, int totalStock, int remaining, int reserved) {

    /** The core correctness rule of the whole project: nothing is created or lost. */
    public boolean isConsistent() {
        return reserved + remaining == totalStock;
    }
}
