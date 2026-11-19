package io.github.samettns.reservation.domain;

/**
 * Stock numbers of one event, read in a single SQL statement so that all values come
 * from the same database snapshot. {@code remaining} is the sum over all stock shards.
 */
public record StockSnapshot(long eventId, int totalStock, int remaining, int reserved, int shards) {

    /** The core correctness rule of the whole project: nothing is created or lost. */
    public boolean isConsistent() {
        return reserved + remaining == totalStock;
    }
}
