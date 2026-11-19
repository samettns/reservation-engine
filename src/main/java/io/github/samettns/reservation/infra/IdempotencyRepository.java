package io.github.samettns.reservation.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency claims. Lifecycle of one (user, key):
 * <pre>
 *   claim()    -> IN_PROGRESS  (own transaction, committed before any stock is touched)
 *   complete() -> COMPLETED    (inside the reservation transaction: atomic with the reservation)
 *   release()  -> deleted      (no side effect happened: sold out, 503, error; the client may retry)
 * </pre>
 */
@Repository
public class IdempotencyRepository {

    public enum Status { IN_PROGRESS, COMPLETED }

    /** {@code live}: for IN_PROGRESS, whether the owner's lease is still valid (DB clock). */
    public record IdempotencyRecord(long eventId, Status status, UUID reservationId, boolean live) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this request. Succeeds if the key is new, or if it is held IN_PROGRESS by
     * an owner whose lease has expired (it crashed) for the SAME event. The conflict check and the
     * takeover are one statement, so two requests can never both win.
     *
     * @return true if this request now owns the key
     */
    public boolean claim(String userId, String key, long eventId, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, idempotency_key, event_id, status, locked_until)
                        VALUES (:userId, :key, :eventId, 'IN_PROGRESS', now() + :leaseMs * interval '1 millisecond')
                        ON CONFLICT (user_id, idempotency_key) DO UPDATE
                            SET locked_until = EXCLUDED.locked_until
                            WHERE idempotency_keys.status = 'IN_PROGRESS'
                              AND idempotency_keys.locked_until < now()
                              AND idempotency_keys.event_id = EXCLUDED.event_id
                        RETURNING 1
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("eventId", eventId)
                .param("leaseMs", lease.toMillis())
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    public Optional<IdempotencyRecord> find(String userId, String key) {
        return jdbc.sql("""
                        SELECT event_id, status, reservation_id, locked_until > now() AS live
                        FROM idempotency_keys WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("userId", userId)
                .param("key", key)
                .query((rs, rowNum) -> new IdempotencyRecord(
                        rs.getLong("event_id"),
                        Status.valueOf(rs.getString("status")),
                        rs.getObject("reservation_id", UUID.class),
                        rs.getBoolean("live")))
                .optional();
    }

    /** Must run in the reservation's transaction. */
    public void complete(String userId, String key, UUID reservationId) {
        jdbc.sql("""
                        UPDATE idempotency_keys SET status = 'COMPLETED', reservation_id = :reservationId
                        WHERE user_id = :userId AND idempotency_key = :key
                        """)
                .param("reservationId", reservationId)
                .param("userId", userId)
                .param("key", key)
                .update();
    }

    /** Gives the key back when the request had no side effect. A COMPLETED key is never deleted. */
    public void release(String userId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE user_id = :userId AND idempotency_key = :key AND status = 'IN_PROGRESS'")
                .param("userId", userId)
                .param("key", key)
                .update();
    }
}
