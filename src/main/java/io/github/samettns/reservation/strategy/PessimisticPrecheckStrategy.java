package io.github.samettns.reservation.strategy;

import io.github.samettns.reservation.strategy.StockShards.RowResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Pessimistic locking with a lock-free pre-check (a "double-checked" lock):
 * <ol>
 *   <li>plain {@code SELECT qty} (no lock): if 0, answer "empty" immediately;</li>
 *   <li>otherwise fall back to the normal {@code SELECT ... FOR UPDATE} path, which
 *       re-checks under the lock.</li>
 * </ol>
 *
 * <p>Why it is still correct: the pre-check can only be stale in the "has stock" direction
 * (stock only goes down, apart from rollbacks), and that case is re-verified under the
 * lock. A stale "0" can only come from a unit that a rolling-back transaction is about to
 * give back, which is the same window every strategy has.
 *
 * <p>Trade-off: when stock is plentiful every request pays one extra round trip.
 */
@Component
public class PessimisticPrecheckStrategy implements ReservationStrategy {

    private final JdbcClient jdbc;
    private final StockShards shards;
    private final PessimisticLockingStrategy locked;

    public PessimisticPrecheckStrategy(JdbcClient jdbc, StockShards shards, PessimisticLockingStrategy locked) {
        this.jdbc = jdbc;
        this.shards = shards;
        this.locked = locked;
    }

    @Override
    public String name() {
        return "pessimistic-precheck";
    }

    @Override
    public boolean tryAcquireOne(long eventId) {
        return shards.acquire(eventId, this::tryRow);
    }

    private RowResult tryRow(long eventId, int shardNo) {
        int qty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId AND shard_no = :shardNo")
                .param("eventId", eventId)
                .param("shardNo", shardNo)
                .query(Integer.class)
                .single();
        if (qty <= 0) {
            return RowResult.EMPTY; // sold out without ever touching the row lock
        }
        return locked.tryRow(eventId, shardNo);
    }
}
