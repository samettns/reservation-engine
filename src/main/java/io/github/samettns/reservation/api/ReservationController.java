package io.github.samettns.reservation.api;

import io.github.samettns.reservation.domain.ConfirmOutcome;
import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
public class ReservationController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    public record ReserveRequest(@NotBlank @Size(max = 64) String userId) {
    }

    /** {@code expiresAt}: payment deadline while HELD (the client shows a countdown). */
    public record ReservationResponse(UUID reservationId, long eventId, String userId, String status,
                                      OffsetDateTime createdAt, OffsetDateTime expiresAt) {
        static ReservationResponse from(Reservation r) {
            return new ReservationResponse(r.id(), r.eventId(), r.userId(), r.status().name(),
                    r.createdAt(), r.expiresAt());
        }
    }

    @PostMapping("/events/{eventId}/reserve")
    public ResponseEntity<?> reserve(@PathVariable long eventId,
                                     @RequestHeader(IDEMPOTENCY_KEY_HEADER) @NotBlank @Size(max = 128) String idempotencyKey,
                                     @Valid @RequestBody ReserveRequest request) {
        ReservationOutcome outcome = service.reserve(eventId, request.userId(), idempotencyKey);

        // Exhaustive switch over a sealed interface: adding a new outcome type
        // becomes a compile error here until it is handled.
        return switch (outcome) {
            case ReservationOutcome.Created(Reservation r) -> ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(ReservationResponse.from(r));
            case ReservationOutcome.Replayed(Reservation r) -> ResponseEntity
                    .ok()
                    .header(REPLAYED_HEADER, "true")
                    .body(ReservationResponse.from(r));
            case ReservationOutcome.SoldOut(long id) -> ResponseEntity
                    .status(HttpStatus.CONFLICT)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Event " + id + " is sold out"));
        };
    }

    /**
     * Payment callback. 200 when confirmed (also on a retried callback), 410 Gone when the
     * payment came after the deadline: the hold no longer exists and will not come back.
     */
    @PostMapping("/reservations/{reservationId}/confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID reservationId) {
        return switch (service.confirm(reservationId)) {
            case ConfirmOutcome.Confirmed(Reservation r) -> ResponseEntity.ok(ReservationResponse.from(r));
            case ConfirmOutcome.AlreadyConfirmed(Reservation r) -> ResponseEntity.ok()
                    .header(REPLAYED_HEADER, "true")
                    .body(ReservationResponse.from(r));
            case ConfirmOutcome.Expired(Reservation r) -> ResponseEntity.status(HttpStatus.GONE)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.GONE,
                            "Hold " + r.id() + " expired at " + r.expiresAt() + "; payment was too late"));
        };
    }
}
