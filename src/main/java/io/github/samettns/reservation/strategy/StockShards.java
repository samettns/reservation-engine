package io.github.samettns.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Spreads reservations over an event's stock shards. Sharding is orthogonal to the
 * locking strategy: each strategy only knows how to take one unit from ONE row
 * ({@link RowAttempt}); this class decides which rows to try and in what order.
 *
 * <p>Order: a random starting shard, then every other shard once (wrapping around).
 * Random start spreads concurrent requests across rows; visiting every shard before
 * answering "sold out" guarantees we never refuse while another shard still has stock.
 * With one shard this is exactly the old single-row behaviour.
 *
 * <p>The shard count is read from the database on every call, NOT cached in memory. An earlier
 * version cached it per event id as "immutable"; the stage 3 benchmark resets the database with
 * {@code TRUNCATE ... RESTART IDENTITY}, ids were reused, and the stale cache made 4- and 16-shard
 * events behave as single-shard ones (only one shard was ever sold). A cache without
 * invalidation traded correctness for one primary-key lookup; not worth it.
 */
@Component
public class StockShards {

    /** What happened when a strategy tried one row. */
    public enum RowResult {
        /** A unit was taken from this row. */
        ACQUIRED,
        /** This row has no stock left. */
        EMPTY,
        /** Gave up on this row because of contention; it may still have stock. */
        CONTENDED
    }

    /** One stock row to try, plus how many rows the event has in total. */
    public record ShardRow(long eventId, int shardNo, int shardCount) {
        public boolean onlyShard() {
            return shardCount == 1;
        }
    }

    @FunctionalInterface
    public interface RowAttempt {
        RowResult tryRow(ShardRow row);
    }

    private final JdbcClient jdbc;

    public StockShards(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} if some shard gave us a unit, {@code false} if every shard was empty
     * @throws ContentionException if no shard gave a unit and at least one gave up due to contention
     */
    public boolean acquire(long eventId, RowAttempt attempt) {
        int shards = shardCount(eventId);
        int start = shards == 1 ? 0 : ThreadLocalRandom.current().nextInt(shards);
        boolean contended = false;
        for (int i = 0; i < shards; i++) {
            ShardRow row = new ShardRow(eventId, (start + i) % shards, shards);
            switch (attempt.tryRow(row)) {
                case ACQUIRED -> {
                    return true;
                }
                case EMPTY -> {
                    // try the next shard
                }
                case CONTENDED -> contended = true;
            }
        }
        if (contended) {
            throw new ContentionException(eventId, "no shard could be acquired; at least one was contended");
        }
        return false;
    }

    public int shardCount(long eventId) {
        return jdbc.sql("SELECT shard_count FROM events WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
    }
}
