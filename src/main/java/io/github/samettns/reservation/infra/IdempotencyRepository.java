package io.github.samettns.reservation.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Idempotency claims. Lifecycle of one (user, key):
 * <pre>
 *   claim()     -> IN_PROGRESS, fence token t  (own transaction, committed before any stock is touched)
 *   lockClaim() -> "still mine with token t?"  (first statement of the reservation transaction; row lock)
 *   complete()  -> COMPLETED                    (same transaction as the reservation)
 *   release()   -> deleted                      (no side effect happened: sold out, 503, error)
 * </pre>
 * A claim is a lease; a takeover after the lease expired increments the fence token, so every
 * ownership-based write of the previous (slow, not dead) owner is rejected.
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
     * Claims the key for this request. Succeeds if the key is new (token 1), or if it is held
     * IN_PROGRESS by an owner whose lease has expired, for the SAME event (token + 1). The conflict
     * check and the takeover are one statement, so two requests can never both win.
     *
     * @return the fence token this request now owns the key with, or empty if it does not own it
     */
    public OptionalLong claim(String userId, String key, long eventId, Duration lease) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (user_id, idempotency_key, event_id, status, locked_until, fence_token)
                        VALUES (:userId, :key, :eventId, 'IN_PROGRESS', now() + :leaseMs * interval '1 millisecond', 1)
                        ON CONFLICT (user_id, idempotency_key) DO UPDATE
                            SET locked_until = EXCLUDED.locked_until,
                                fence_token  = idempotency_keys.fence_token + 1
                            WHERE idempotency_keys.status = 'IN_PROGRESS'
                              AND idempotency_keys.locked_until < now()
                              AND idempotency_keys.event_id = EXCLUDED.event_id
                        RETURNING fence_token
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("eventId", eventId)
                .param("leaseMs", lease.toMillis())
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
    }

    /**
     * First statement of the reservation transaction: "do I still own this key with this token?"
     * Also locks the claim row until the transaction ends, so nobody can take the key over while
     * this transaction is working (a takeover's ON CONFLICT DO UPDATE waits for the lock, then sees
     * COMPLETED, or IN_PROGRESS with a still valid lease, and backs off).
     *
     * @return false if the lease was taken over: the caller must abort before touching stock
     */
    public boolean lockClaim(String userId, String key, long fenceToken) {
        return jdbc.sql("""
                        SELECT 1 FROM idempotency_keys
                        WHERE user_id = :userId AND idempotency_key = :key
                          AND status = 'IN_PROGRESS' AND fence_token = :token
                        FOR UPDATE
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("token", fenceToken)
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

    /**
     * Must run in the reservation's transaction, after {@link #lockClaim}. Conditional on the token
     * as well (defence in depth): a stale owner can never mark the key COMPLETED.
     *
     * @return false if this request no longer owns the key (the caller must roll back)
     */
    public boolean complete(String userId, String key, UUID reservationId, long fenceToken) {
        return jdbc.sql("""
                        UPDATE idempotency_keys SET status = 'COMPLETED', reservation_id = :reservationId
                        WHERE user_id = :userId AND idempotency_key = :key
                          AND status = 'IN_PROGRESS' AND fence_token = :token
                        """)
                .param("reservationId", reservationId)
                .param("userId", userId)
                .param("key", key)
                .param("token", fenceToken)
                .update() == 1;
    }

    /**
     * Gives the key back when the request had no side effect. Only the CURRENT owner can do that:
     * a stale owner's token does not match, so it cannot delete the claim of whoever took over.
     */
    public void release(String userId, String key, long fenceToken) {
        jdbc.sql("""
                        DELETE FROM idempotency_keys
                        WHERE user_id = :userId AND idempotency_key = :key
                          AND status = 'IN_PROGRESS' AND fence_token = :token
                        """)
                .param("userId", userId)
                .param("key", key)
                .param("token", fenceToken)
                .update();
    }
}
