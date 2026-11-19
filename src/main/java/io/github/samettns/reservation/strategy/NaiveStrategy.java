package io.github.samettns.reservation.strategy;

import io.github.samettns.reservation.strategy.StockShards.RowResult;
import io.github.samettns.reservation.strategy.StockShards.ShardRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
    private final StockShards shards;
    private final Duration raceWindow;

    /** Production constructor: no artificial delay, and there is no property to turn one on. */
    @Autowired
    public NaiveStrategy(JdbcClient jdbc, StockShards shards) {
        this(jdbc, shards, Duration.ZERO);
    }

    /**
     * Test-only: widens the gap between read and write so that concurrent transactions
     * deterministically read the same value. Package-private and not reachable from
     * configuration, so the production bean can never get a delay.
     */
    NaiveStrategy(JdbcClient jdbc, StockShards shards, Duration raceWindow) {
        this.jdbc = jdbc;
        this.shards = shards;
        this.raceWindow = raceWindow;
    }

    @Override
    public String name() {
        return "naive";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        return shards.acquire(eventId, this::tryRow);
    }

    private RowResult tryRow(ShardRow row) {
        int qty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId AND shard_no = :shardNo")
                .param("eventId", row.eventId())
                .param("shardNo", row.shardNo())
                .query(Integer.class)
                .single();
        if (qty <= 0) {
            return RowResult.EMPTY;
        }
        // Race window: other transactions may have read the same qty by now.
        pauseForTests();
        jdbc.sql("UPDATE stock SET qty = :newQty WHERE event_id = :eventId AND shard_no = :shardNo")
                .param("newQty", qty - 1)
                .param("eventId", row.eventId())
                .param("shardNo", row.shardNo())
                .update();
        return RowResult.ACQUIRED;
    }

    private void pauseForTests() {
        if (raceWindow.isZero()) {
            return;
        }
        try {
            Thread.sleep(raceWindow);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
