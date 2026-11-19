package io.github.samettns.reservation.strategy;

import io.github.samettns.reservation.strategy.StockShards.RowResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lock the row first ({@code SELECT ... FOR UPDATE}), then check and write. Other
 * transactions block on the SELECT until we commit, so check-then-write is safe.
 *
 * <p>Cost: the lock is held until the END of the caller's transaction (including the
 * reservation INSERT), and every waiter holds a pooled DB connection while it waits.
 * Even a "sold out" answer takes the lock, so rejections queue too (see
 * {@link PessimisticPrecheckStrategy} for the fix measured in stage 3).
 *
 * <p><b>Deadlock avoidance with sharding.</b> Probing shards in a random order while keeping
 * the lock on every empty shard we looked at deadlocks: T1 holds empty shard 3 and waits for
 * shard 5, T2 holds empty shard 5 and waits for shard 3 (found by the stage 3 tests). The lock
 * on an empty shard is useless, so it is released immediately by taking it inside a savepoint
 * and rolling back to that savepoint. A transaction then never holds a stock lock while
 * waiting for another one, which breaks the "hold and wait" condition a deadlock needs.
 * (Verified: in PostgreSQL ROLLBACK TO SAVEPOINT releases row locks taken after the savepoint;
 * RELEASE SAVEPOINT keeps them.)
 */
@Component
public class PessimisticLockingStrategy implements ReservationStrategy {

    private static final String SAVEPOINT = "shard_probe";

    private final JdbcClient jdbc;
    private final StockShards shards;

    public PessimisticLockingStrategy(JdbcClient jdbc, StockShards shards) {
        this.jdbc = jdbc;
        this.shards = shards;
    }

    @Override
    public String name() {
        return "pessimistic";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        return shards.acquire(eventId, this::tryRow);
    }

    RowResult tryRow(long eventId, int shardNo) {
        // With a single row there is no other stock lock to wait for, so no deadlock risk
        // and no need to pay for the extra savepoint round trips.
        boolean multiShard = shards.shardCount(eventId) > 1;
        if (multiShard) {
            jdbc.sql("SAVEPOINT " + SAVEPOINT).update();
        }
        int qty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId AND shard_no = :shardNo FOR UPDATE")
                .param("eventId", eventId)
                .param("shardNo", shardNo)
                .query(Integer.class)
                .single();
        if (qty <= 0) {
            if (multiShard) {
                jdbc.sql("ROLLBACK TO SAVEPOINT " + SAVEPOINT).update(); // drop the useless lock now
            }
            return RowResult.EMPTY;
        }
        if (multiShard) {
            jdbc.sql("RELEASE SAVEPOINT " + SAVEPOINT).update(); // keep the lock until commit
        }
        jdbc.sql("UPDATE stock SET qty = qty - 1 WHERE event_id = :eventId AND shard_no = :shardNo")
                .param("eventId", eventId)
                .param("shardNo", shardNo)
                .update();
        return RowResult.ACQUIRED;
    }
}
