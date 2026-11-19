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
 * </ul>
 */
@Component
public class RedisGateStrategy implements ReservationStrategy {

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
    public boolean tryAcquireOne(long eventId) {
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
            return false;
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

        boolean acquiredInDatabase = database.tryAcquireOne(eventId);
        if (!acquiredInDatabase) {
            log.warn("Redis/DB drift on event {}: Redis allowed a reservation but the DB is sold out", eventId);
        }
        return acquiredInDatabase;
    }

    private long runGate(String key) {
        return redis.execute(TRY_DECREMENT, List.of(key));
    }

    /**
     * Lazy initialisation from the DB. SET NX makes it safe when many requests (or
     * instances) race to initialise: only the first one writes, the rest keep its value.
     */
    private void initialiseFromDatabase(long eventId, String key) {
        int dbQty = jdbc.sql("SELECT qty FROM stock WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
        redis.opsForValue().setIfAbsent(key, Integer.toString(dbQty));
    }
}
