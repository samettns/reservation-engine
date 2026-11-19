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
                               (SELECT count(*) FROM reservations r WHERE r.event_id = e.id) AS reserved
                        FROM events e
                        WHERE e.id = :id
                        """)
                .param("id", eventId)
                .query((rs, rowNum) -> new StockSnapshot(
                        rs.getLong("id"),
                        rs.getInt("total_stock"),
                        rs.getInt("remaining"),
                        rs.getInt("reserved"),
                        rs.getInt("shard_count")))
                .optional();
    }

    /** Remaining stock per shard, ordered by shard number. */
    public List<Integer> shardQuantities(long eventId) {
        return jdbc.sql("SELECT qty FROM stock WHERE event_id = :id ORDER BY shard_no")
                .param("id", eventId)
                .query(Integer.class)
                .list();
    }
}
