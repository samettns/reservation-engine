package io.github.samettns.reservation.domain;

/**
 * Stage 0 only knows CONFIRMED. HELD / EXPIRED arrive with temporary holds (stage 4).
 */
public enum ReservationStatus {
    CONFIRMED
}
