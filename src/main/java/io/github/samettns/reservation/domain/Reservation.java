package io.github.samettns.reservation.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Reservation(
        UUID id,
        long eventId,
        int shardNo,
        String userId,
        String idempotencyKey,
        ReservationStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt) {
}
