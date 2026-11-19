package io.github.samettns.reservation.infra;

import io.github.samettns.reservation.domain.StockSnapshot;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the event and its stock row together, so an event never exists without stock. */
    @Transactional
    public long create(String name, int totalStock) {
        long eventId = jdbc.sql("INSERT INTO events (name, total_stock) VALUES (:name, :total) RETURNING id")
                .param("name", name)
                .param("total", totalStock)
                .query(Long.class)
                .single();
        jdbc.sql("INSERT INTO stock (event_id, qty) VALUES (:eventId, :qty)")
                .param("eventId", eventId)
                .param("qty", totalStock)
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
     * One statement = one snapshot (READ COMMITTED takes a snapshot per statement), so
     * remaining and reserved are consistent with each other even while writes are running.
     * Two separate SELECTs could see different moments and report a false invariant violation.
     */
    public Optional<StockSnapshot> findStock(long eventId) {
        return jdbc.sql("""
                        SELECT e.id,
                               e.total_stock,
                               s.qty,
                               (SELECT count(*) FROM reservations r WHERE r.event_id = e.id) AS reserved
                        FROM events e
                        JOIN stock s ON s.event_id = e.id
                        WHERE e.id = :id
                        """)
                .param("id", eventId)
                .query((rs, rowNum) -> new StockSnapshot(
                        rs.getLong("id"),
                        rs.getInt("total_stock"),
                        rs.getInt("qty"),
                        rs.getInt("reserved")))
                .optional();
    }
}
