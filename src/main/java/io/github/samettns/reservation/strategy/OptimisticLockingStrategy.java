package io.github.samettns.reservation.strategy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Read the row with its version, then write only if the version is unchanged
 * ("compare-and-set"). If someone else wrote first, 0 rows are updated and we retry
 * with a fresh read. This is what JPA's {@code @Version} does, written out by hand.
 *
 * <p>Retrying inside the same transaction is correct here because under READ COMMITTED
 * every statement sees the latest committed data, so the re-read is fresh.
 *
 * <p>Caveat specific to PostgreSQL: the conditional UPDATE still takes a row lock and a
 * loser waits for the winner to commit before seeing "0 rows". So "optimistic" here means
 * "no lock between read and write", not "never waits".
 */
@Component
public class OptimisticLockingStrategy implements ReservationStrategy {

    private record VersionedStock(int qty, long version) {
    }

    private final JdbcClient jdbc;
    private final int maxAttempts;

    public OptimisticLockingStrategy(JdbcClient jdbc,
                                     @Value("${reservation.optimistic.max-attempts:10}") int maxAttempts) {
        this.jdbc = jdbc;
        this.maxAttempts = maxAttempts;
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            VersionedStock current = jdbc.sql("SELECT qty, version FROM stock WHERE event_id = :eventId")
                    .param("eventId", eventId)
                    .query((rs, rowNum) -> new VersionedStock(rs.getInt("qty"), rs.getLong("version")))
                    .single();
            if (current.qty() <= 0) {
                return false;
            }
            int updated = jdbc.sql("""
                            UPDATE stock
                            SET qty = qty - 1, version = version + 1
                            WHERE event_id = :eventId AND version = :version
                            """)
                    .param("eventId", eventId)
                    .param("version", current.version())
                    .update();
            if (updated == 1) {
                return true;
            }
            // Lost the race: someone changed the row after our read. Re-read and try again.
            // No sleep/backoff on purpose: we are inside a transaction holding a DB
            // connection, so sleeping would block a pooled connection while doing nothing.
        }
        throw new ContentionException(eventId, maxAttempts);
    }
}
