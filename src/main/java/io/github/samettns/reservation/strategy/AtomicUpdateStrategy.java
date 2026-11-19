package io.github.samettns.reservation.strategy;

import java.util.OptionalInt;
import io.github.samettns.reservation.strategy.StockShards.RowResult;
import io.github.samettns.reservation.strategy.StockShards.ShardRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Check and decrement in ONE statement. PostgreSQL takes a row lock for the UPDATE and
 * re-evaluates {@code qty > 0} against the latest committed row version, so two
 * concurrent requests can never both take the last unit. No explicit lock, no retry.
 * An UPDATE whose condition is false changes nothing and takes no lock, which is why
 * "sold out" answers are cheap and run in parallel.
 */
@Component
public class AtomicUpdateStrategy implements ReservationStrategy {

    private final JdbcClient jdbc;
    private final StockShards shards;

    public AtomicUpdateStrategy(JdbcClient jdbc, StockShards shards) {
        this.jdbc = jdbc;
        this.shards = shards;
    }

    @Override
    public String name() {
        return "atomic";
    }

    @Override
    public OptionalInt tryAcquireOne(long eventId) {
        return shards.acquire(eventId, this::tryRow);
    }

    private RowResult tryRow(ShardRow row) {
        int updatedRows = jdbc.sql("""
                        UPDATE stock SET qty = qty - 1
                        WHERE event_id = :eventId AND shard_no = :shardNo AND qty > 0
                        """)
                .param("eventId", row.eventId())
                .param("shardNo", row.shardNo())
                .update();
        return updatedRows == 1 ? RowResult.ACQUIRED : RowResult.EMPTY;
    }
}
