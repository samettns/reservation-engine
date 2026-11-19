package io.github.samettns.reservation.domain;

/**
 * <pre>
 *   HELD --(payment before the deadline)--> CONFIRMED
 *   HELD --(deadline passed, sweeper)-----> EXPIRED   (unit returned to its shard)
 * </pre>
 * CONFIRMED and EXPIRED are final.
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    EXPIRED
}
