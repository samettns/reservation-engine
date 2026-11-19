package io.github.samettns.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lock the row first ({@code SELECT ... FOR UPDATE}), then check and write. Other
 * transactions block on the SELECT until we commit, so check-then-write is safe.
 *
 * <p>Cost: the lock is held until the END of the caller's transaction (including the
 * reservation INSERT), and every waiter holds a pooled DB connection while it waits.
 * Under high contention this serialises everything and can exhaust the connection pool.
 */
@Component
public class PessimisticLockingStrategy implements ReservationStrategy {

    private final JdbcClient jdbc;

    public PessimisticLockingStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        int qty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId FOR UPDATE")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        if (qty <= 0) {
            return false;
        }
        jdbc.sql("UPDATE stock SET qty = qty - 1 WHERE event_id = :eventId")
                .param("eventId", eventId)
                .update();
        return true;
    }
}
