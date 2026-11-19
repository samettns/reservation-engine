package io.github.samettns.reservation.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.samettns.reservation.infra.OutboxRepository;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes reservation lifecycle events to the outbox. Every method must be called inside the
 * transaction that performs the state change: the event and the change commit (or roll back)
 * together. That is the whole point of the outbox.
 */
@Component
public class ReservationEvents {

    public static final String HELD = "ReservationHeld";
    public static final String CONFIRMED = "ReservationConfirmed";
    public static final String EXPIRED = "ReservationExpired";

    /** Event body. {@code ticketEventId} is the concert/sale, not the message id. */
    public record Payload(String type, UUID reservationId, long ticketEventId, int shardNo,
                          String userId, String status, OffsetDateTime expiresAt) {
    }

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public ReservationEvents(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void held(Reservation r) {
        record(HELD, r, ReservationStatus.HELD);
    }

    public void confirmed(Reservation r) {
        record(CONFIRMED, r, ReservationStatus.CONFIRMED);
    }

    public void expired(UUID reservationId, long ticketEventId, int shardNo) {
        write(new Payload(EXPIRED, reservationId, ticketEventId, shardNo, null,
                ReservationStatus.EXPIRED.name(), null));
    }

    private void record(String type, Reservation r, ReservationStatus status) {
        write(new Payload(type, r.id(), r.eventId(), r.shardNo(), r.userId(), status.name(), r.expiresAt()));
    }

    private void write(Payload payload) {
        try {
            outbox.add(payload.reservationId(), payload.type(), json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + payload, e);
        }
    }
}
