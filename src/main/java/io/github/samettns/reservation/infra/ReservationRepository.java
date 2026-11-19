package io.github.samettns.reservation.infra;

import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> ROW_MAPPER = (rs, rowNum) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getLong("event_id"),
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Throws {@link org.springframework.dao.DuplicateKeyException} if the same
     * (user, idempotency key) already exists, including when a concurrent request
     * inserted it a moment ago.
     */
    public Reservation insert(long eventId, String userId, String idempotencyKey, ReservationStatus status) {
        return jdbc.sql("""
                        INSERT INTO reservations (event_id, user_id, idempotency_key, status)
                        VALUES (:eventId, :userId, :key, :status)
                        RETURNING *
                        """)
                .param("eventId", eventId)
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("status", status.name())
                .query(ROW_MAPPER)
                .single();
    }

    public Optional<Reservation> findByIdempotencyKey(String userId, String idempotencyKey) {
        return jdbc.sql("SELECT * FROM reservations WHERE user_id = :userId AND idempotency_key = :key")
                .param("userId", userId)
                .param("key", idempotencyKey)
                .query(ROW_MAPPER)
                .optional();
    }
}
