package io.github.samettns.reservation.domain;

/**
 * Stock numbers of one event, read in a single SQL statement so that all values come
 * from the same database snapshot. {@code remaining} is the sum over all stock shards.
 *
 * <p>{@code reserved} = units currently taken = {@code held + confirmed}. EXPIRED reservations
 * gave their unit back, so they are counted only for information.
 */
public record StockSnapshot(long eventId, int totalStock, int remaining, int reserved,
                            int held, int confirmed, int expired, int shards) {

    /** The core correctness rule of the whole project: held + confirmed + remaining = total. */
    public boolean isConsistent() {
        return held + confirmed + remaining == totalStock && reserved == held + confirmed;
    }
}
