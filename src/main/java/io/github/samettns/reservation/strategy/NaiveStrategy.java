package io.github.samettns.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * ⚠️ INTENTIONALLY BROKEN. Exists only to demonstrate overselling.
 *
 * <p>Read-check-write in separate statements. Between the SELECT and the UPDATE another
 * transaction can read the same value; both then write {@code read - 1} and both create a
 * reservation (a "lost update"). The {@code CHECK (qty >= 0)} constraint does not help:
 * the written value is never negative, it is just wrong.
 *
 * <p>Note that it writes the value it read ({@code SET qty = :newQty}), not
 * {@code qty = qty - 1}. The latter would re-read the row under the row lock and hide the bug.
 */
@Component
public class NaiveStrategy implements ReservationStrategy {

    private final JdbcClient jdbc;

    public NaiveStrategy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        int qty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        if (qty <= 0) {
            return false;
        }
        // Race window: other transactions may have read the same qty by now.
        jdbc.sql("UPDATE stock SET qty = :newQty WHERE event_id = :eventId")
                .param("newQty", qty - 1)
                .param("eventId", eventId)
                .update();
        return true;
    }
}
