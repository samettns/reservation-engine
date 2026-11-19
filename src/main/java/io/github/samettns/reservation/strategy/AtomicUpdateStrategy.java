package io.github.samettns.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Check and decrement in ONE statement. PostgreSQL takes a row lock for the UPDATE and
 * re-evaluates {@code qty > 0} against the latest committed row version, so two
 * concurrent requests can never both take the last unit. No explicit lock, no retry.
 */
@Component
public class AtomicUpdateStrategy implements ReservationStrategy {

    private final JdbcClient jdbc;

    public AtomicUpdateStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "atomic";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        int updatedRows = jdbc.sql("UPDATE stock SET qty = qty - 1 WHERE event_id = :eventId AND qty > 0")
                .param("eventId", eventId)
                .update();
        return updatedRows == 1;
    }
}
