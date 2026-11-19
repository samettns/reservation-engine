package io.github.samettns.reservation.strategy;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    @FunctionalInterface
    public interface RowAttempt {
        RowResult tryRow(long eventId, int shardNo);
    }

    private final JdbcClient jdbc;
    /** Shard count never changes after an event is created, so it can be cached forever. */
    private final Map<Long, Integer> shardCounts = new ConcurrentHashMap<>();

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
            int shardNo = (start + i) % shards;
            switch (attempt.tryRow(eventId, shardNo)) {
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
        // Deliberately not computeIfAbsent: it holds a lock while computing, and a blocking DB call
        // inside it would pin the virtual thread to its carrier (Java 21). Two threads may both query
        // on a cold cache; the value is immutable, so either result is correct.
        Integer cached = shardCounts.get(eventId);
        if (cached != null) {
            return cached;
        }
        int count = jdbc.sql("SELECT shard_count FROM events WHERE id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
        shardCounts.putIfAbsent(eventId, count);
        return count;
    }
}
