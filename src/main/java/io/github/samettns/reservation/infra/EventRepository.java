package io.github.samettns.reservation.infra;

import io.github.samettns.reservation.domain.StockSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Single-shard event: one stock row, the classic hot row. */
    public long create(String name, int totalStock) {
        return create(name, totalStock, 1);
    }

    /**
     * Creates the event and its stock shards together, so an event never exists without stock.
     * Stock is split as evenly as possible: the first {@code totalStock % shards} shards get one
     * extra unit. With more shards than units, some shards start empty (allowed).
     */
    @Transactional
    public long create(String name, int totalStock, int shards) {
        long eventId = jdbc.sql("""
                        INSERT INTO events (name, total_stock, shard_count)
                        VALUES (:name, :total, :shards)
                        RETURNING id
                        """)
                .param("name", name)
                .param("total", totalStock)
                .param("shards", shards)
                .query(Long.class)
                .single();
        jdbc.sql("""
                        INSERT INTO stock (event_id, shard_no, qty)
                        SELECT :eventId, s, :base + CASE WHEN s < :remainder THEN 1 ELSE 0 END
                        FROM generate_series(0, :shards - 1) AS s
                        """)
                .param("eventId", eventId)
                .param("base", totalStock / shards)
                .param("remainder", totalStock % shards)
                .param("shards", shards)
                .update();
        return eventId;
    }

    public boolean exists(long eventId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM events WHERE id = :id)")
                .param("id", eventId)
                .query(Boolean.class)
                .single();
    }

    /**
     * One statement = one snapshot (READ COMMITTED takes a snapshot per statement), so the
     * shard sum and the reservation count are consistent with each other even while writes
     * are running. Separate SELECTs could see different moments and report a false violation.
     */
    public Optional<StockSnapshot> findStock(long eventId) {
        return jdbc.sql("""
                        SELECT e.id,
                               e.total_stock,
                               e.shard_count,
                               (SELECT COALESCE(SUM(s.qty), 0) FROM stock s WHERE s.event_id = e.id) AS remaining,
                               r.held,
                               r.confirmed,
                               r.expired
                        FROM events e
                        CROSS JOIN LATERAL (
                            SELECT count(*) FILTER (WHERE status = 'HELD')      AS held,
                                   count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                                   count(*) FILTER (WHERE status = 'EXPIRED')   AS expired
                            FROM reservations WHERE event_id = e.id
                        ) r
                        WHERE e.id = :id
                        """)
                .param("id", eventId)
                .query((rs, rowNum) -> new StockSnapshot(
                        rs.getLong("id"),
                        rs.getInt("total_stock"),
                        rs.getInt("remaining"),
                        rs.getInt("held") + rs.getInt("confirmed"),
                        rs.getInt("held"),
                        rs.getInt("confirmed"),
                        rs.getInt("expired"),
                        rs.getInt("shard_count")))
                .optional();
    }

    /** Existence plus the versioned sold-out marker, in one primary-key read. */
    public record Availability(long stockVersion, boolean soldOut) {
    }

    public Optional<Availability> findAvailability(long eventId) {
        return jdbc.sql("""
                        SELECT stock_version, sold_out_version = stock_version AS sold_out
                        FROM events WHERE id = :id
                        """)
                .param("id", eventId)
                .query((rs, rowNum) -> new Availability(rs.getLong("stock_version"), rs.getBoolean("sold_out")))
                .optional();
    }

    /**
     * Marks the event sold out AS OF {@code observedStockVersion} (read before the shards were
     * probed). Compare-and-set: if units were returned in the meantime, the stock version moved on
     * and nothing is marked, so the marker can never hide stock that came back.
     */
    public boolean markSoldOut(long eventId, long observedStockVersion) {
        return jdbc.sql("""
                        UPDATE events SET sold_out_version = :v
                        WHERE id = :id AND stock_version = :v AND sold_out_version <> :v
                        """)
                .param("v", observedStockVersion)
                .param("id", eventId)
                .update() == 1;
    }

    /** Called when units come back (expired holds): invalidates any sold-out marker of this event. */
    public void bumpStockVersion(long eventId) {
        jdbc.sql("UPDATE events SET stock_version = stock_version + 1 WHERE id = :id")
                .param("id", eventId)
                .update();
    }

    /**
     * Gives {@code units} back to one shard. Bumps the version too, so an optimistic-locking
     * reader that saw the old row retries instead of overwriting the returned units.
     */
    public void returnStock(long eventId, int shardNo, int units) {
        jdbc.sql("""
                        UPDATE stock SET qty = qty + :units, version = version + 1
                        WHERE event_id = :eventId AND shard_no = :shardNo
                        """)
                .param("units", units)
                .param("eventId", eventId)
                .param("shardNo", shardNo)
                .update();
    }

    /** Remaining stock per shard, ordered by shard number. */
    public List<Integer> shardQuantities(long eventId) {
        return jdbc.sql("SELECT qty FROM stock WHERE event_id = :id ORDER BY shard_no")
                .param("id", eventId)
                .query(Integer.class)
                .list();
    }
}
