package io.github.samettns.reservation.infra;

import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> ROW_MAPPER = (rs, rowNum) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getLong("event_id"),
            rs.getInt("shard_no"),
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            ReservationStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class));

    /** A hold that the sweeper turned into EXPIRED; its unit must go back to this shard. */
    public record ExpiredHold(UUID id, long eventId, int shardNo) {
    }

    private final JdbcClient jdbc;

    public ReservationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a HELD reservation. The deadline uses the DATABASE clock ({@code now()}), the same
     * clock the confirm and expiry queries compare against, so clock differences between app
     * instances cannot make a hold expire early or late.
     *
     * <p>Throws {@link org.springframework.dao.DuplicateKeyException} if the same
     * (user, idempotency key) already exists, including when a concurrent request inserted it
     * a moment ago.
     */
    public Reservation insertHeld(long eventId, int shardNo, String userId, String idempotencyKey, Duration ttl) {
        return jdbc.sql("""
                        INSERT INTO reservations (event_id, shard_no, user_id, idempotency_key, status, expires_at)
                        VALUES (:eventId, :shardNo, :userId, :key, 'HELD', now() + :ttlMs * interval '1 millisecond')
                        RETURNING *
                        """)
                .param("eventId", eventId)
                .param("shardNo", shardNo)
                .param("userId", userId)
                .param("key", idempotencyKey)
                .param("ttlMs", ttl.toMillis())
                .query(ROW_MAPPER)
                .single();
    }

    /** For the stock-last variant: the hold was inserted before the strategy picked its shard. */
    public Reservation moveToShard(UUID id, int shardNo) {
        return jdbc.sql("UPDATE reservations SET shard_no = :shardNo WHERE id = :id RETURNING *")
                .param("shardNo", shardNo)
                .param("id", id)
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

    public Optional<Reservation> findById(UUID id) {
        return jdbc.sql("SELECT * FROM reservations WHERE id = :id")
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * HELD -> CONFIRMED, only if still HELD and before the deadline. The UPDATE takes the row lock;
     * if the sweeper got there first, PostgreSQL re-checks {@code status = 'HELD'} on the newest
     * row version after the sweeper commits, finds EXPIRED, and updates nothing. Exactly one of
     * the two transitions can win.
     *
     * @return the confirmed reservation, or empty if it was not HELD or the deadline had passed
     */
    public Optional<Reservation> confirmIfHeld(UUID id) {
        return jdbc.sql("""
                        UPDATE reservations
                        SET status = 'CONFIRMED', confirmed_at = now()
                        WHERE id = :id AND status = 'HELD' AND expires_at > now()
                        RETURNING *
                        """)
                .param("id", id)
                .query(ROW_MAPPER)
                .optional();
    }

    /**
     * Claims up to {@code limit} holds whose deadline has passed and marks them EXPIRED, in one
     * statement. Two guards against expiring (and returning stock for) a hold twice:
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED}: rows another sweeper (or a confirm) is working on are
     *       skipped instead of waited for, so parallel sweepers split the work instead of
     *       queueing behind each other or picking the same rows.</li>
     *   <li>{@code AND r.status = 'HELD'} in the UPDATE: the transition itself is conditional, so
     *       even if a row were somehow selected twice, only one transaction could change it.</li>
     * </ul>
     * Must run inside a transaction; the caller returns the stock in the same transaction.
     */
    public List<ExpiredHold> expireDueHolds(int limit) {
        return jdbc.sql("""
                        WITH due AS (
                            SELECT id FROM reservations
                            WHERE status = 'HELD' AND expires_at <= now()
                            ORDER BY expires_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE reservations r
                        SET status = 'EXPIRED', expired_at = now()
                        FROM due
                        WHERE r.id = due.id AND r.status = 'HELD'
                        RETURNING r.id, r.event_id, r.shard_no
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new ExpiredHold(
                        rs.getObject("id", UUID.class), rs.getLong("event_id"), rs.getInt("shard_no")))
                .list();
    }
}
