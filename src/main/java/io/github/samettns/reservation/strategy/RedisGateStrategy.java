package io.github.samettns.reservation.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.OptionalInt;

/**
 * Redis as an admission gate in front of the database.
 *
 * <p>A Lua script checks and decrements a counter atomically in Redis (Redis runs a
 * script without interleaving other commands). Only requests that pass the gate touch
 * PostgreSQL, where the {@link AtomicUpdateStrategy} UPDATE is still executed. So when
 * 5,000 people chase 100 tickets, ~4,900 are turned away in Redis without ever taking a
 * DB connection or contending for the row lock.
 *
 * <p><b>Source of truth is PostgreSQL.</b> Redis is a cache-like gate that may be <i>lower</i>
 * than the DB (safe: we undersell a little until reconciled) but must never be
 * <i>higher</i> in a way that matters, because the DB UPDATE has the final say anyway.
 * Drift cases:
 * <ul>
 *   <li>DB transaction rolls back after Redis was decremented: compensated with INCR in an
 *       after-completion callback.</li>
 *   <li>Process crashes between DECR and commit: the callback never runs, Redis stays one
 *       lower than the DB. Needs a reconciliation job (stage 7).</li>
 *   <li>Redis says yes but the DB says sold out: we trust the DB and log the drift.</li>
 *   <li>Expired holds return stock to the DB: mirrored into Redis after the DB commit
 *       ({@link #onStockReturned}).</li>
 * </ul>
 */
@Component
public class RedisGateStrategy implements ReservationStrategy, StockReturnListener {

    private static final Logger log = LoggerFactory.getLogger(RedisGateStrategy.class);

    static final long NOT_INITIALISED = -1;
    static final long SOLD_OUT = 0;
    static final long ACQUIRED = 1;

    /** Returns -1 if the counter does not exist, 0 if sold out, 1 if a unit was taken. */
    private static final RedisScript<Long> TRY_DECREMENT = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                return -1
            end
            if tonumber(current) <= 0 then
                return 0
            end
            redis.call('DECR', KEYS[1])
            return 1
            """, Long.class);

    /** INCRBY only if the counter exists (never creates a counter from a partial value). */
    private static final RedisScript<Long> INCREMENT_IF_PRESENT = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final AtomicUpdateStrategy database;

    public RedisGateStrategy(StringRedisTemplate redis, JdbcClient jdbc, AtomicUpdateStrategy database) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.database = database;
    }

    public static String stockKey(long eventId) {
        return "stock:" + eventId;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public OptionalInt tryAcquireOne(long eventId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // Without a transaction we could not undo the Redis decrement on rollback.
            throw new IllegalStateException("RedisGateStrategy must run inside a transaction");
        }
        String key = stockKey(eventId);

        long result = runGate(key);
        if (result == NOT_INITIALISED) {
            initialiseFromDatabase(eventId, key);
            result = runGate(key);
        }
        if (result == SOLD_OUT) {
            return OptionalInt.empty();
        }

        // From here on Redis has been decremented: give the unit back if the DB transaction
        // does not commit (strategy fails below, reservation INSERT fails, duplicate key...).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    redis.opsForValue().increment(key);
                }
            }
        });

        OptionalInt shard = database.tryAcquireOne(eventId);
        if (shard.isEmpty()) {
            log.warn("Redis/DB drift on event {}: Redis allowed a reservation but the DB is sold out", eventId);
        }
        return shard;
    }

    /**
     * An expired hold gave units back to the DB: give them back to the gate too, otherwise Redis
     * keeps saying "sold out" while the DB has stock again. Applied only AFTER the DB commit (if
     * the return rolls back, Redis must not change). If the process dies between the commit and
     * this INCRBY, Redis stays lower than the DB: the safe direction (undersell), fixed by the
     * reconciliation job planned for stage 7. A missing key is left alone: lazy initialisation
     * will read the already-returned stock from the DB.
     */
    @Override
    public void onStockReturned(long eventId, int units) {
        String key = stockKey(eventId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                redis.execute(INCREMENT_IF_PRESENT, List.of(key), Integer.toString(units));
            }
        });
    }

    private long runGate(String key) {
        return redis.execute(TRY_DECREMENT, List.of(key));
    }

    /**
     * Lazy initialisation from the DB. SET NX makes it safe when many requests (or
     * instances) race to initialise: only the first one writes, the rest keep its value.
     */
    private void initialiseFromDatabase(long eventId, String key) {
        // One Redis counter per event, whatever the number of DB shards: the gate only needs
        // the total; the DB step (AtomicUpdateStrategy) picks the shard.
        int dbQty = jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM stock WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        redis.opsForValue().setIfAbsent(key, Integer.toString(dbQty));
    }
}
