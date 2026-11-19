package io.github.samettns.reservation.reconciliation;

import io.github.samettns.reservation.reconciliation.RedisLock.Lease;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings every Redis gate counter back to the database's real remaining stock.
 *
 * <p>Why drift happens: the gate is decremented in Redis before the DB transaction, and given back
 * in after-commit/after-rollback callbacks. If the process dies in between, the callback never
 * runs and Redis stays LOWER than the DB: units that exist can no longer be sold. (Redis can never
 * be meaningfully higher: the DB's atomic UPDATE has the final say.)
 *
 * <p>Runs on every instance on a schedule, but a {@link RedisLock} lets only one of them do the
 * work per round. Every write to a counter is FENCED: a Lua script compares the writer's token with
 * the newest token issued; a writer whose lock expired and was re-acquired by someone else is
 * rejected atomically, inside Redis, even if it does not know it lost the lock.
 *
 * <p>Known, accepted imprecision: requests that are in flight while the DB is read have already
 * decremented Redis but not yet committed in the DB, so the counter can be set a few units too high
 * for a moment. That is safe (the DB still refuses overselling) and corrects itself.
 */
@Component
public class RedisStockReconciler {

    private static final Logger log = LoggerFactory.getLogger(RedisStockReconciler.class);

    public static final String LOCK_NAME = "redis-stock-reconciler";

    /** Result of one fenced write. */
    public enum WriteResult { CORRECTED, UNCHANGED, COUNTER_GONE, REJECTED_STALE_TOKEN }

    /**
     * KEYS[1] = fence counter, KEYS[2] = gate counter; ARGV[1] = writer's token, ARGV[2] = target value.
     * Atomic in Redis: no newer lock acquisition can slip in between the check and the write.
     */
    private static final RedisScript<Long> FENCED_SET = RedisScript.of("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return -1
            end
            local current = redis.call('GET', KEYS[2])
            if not current then
                return -2
            end
            if current == ARGV[2] then
                return 0
            end
            redis.call('SET', KEYS[2], ARGV[2])
            return 1
            """, Long.class);

    private final RedisLock lock;
    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final Duration lockTtl;
    private final Counter acquired;
    private final Counter skipped;
    private final Counter corrections;
    private final Counter staleRejected;

    @Autowired
    public RedisStockReconciler(RedisLock lock, StringRedisTemplate redis, JdbcClient jdbc,
                                @Value("${reservation.reconciler.lock-ttl:PT10S}") Duration lockTtl,
                                MeterRegistry meters) {
        this.lock = lock;
        this.redis = redis;
        this.jdbc = jdbc;
        this.lockTtl = lockTtl;
        this.acquired = Counter.builder("reservation.reconciler.runs").tag("outcome", "acquired").register(meters);
        this.skipped = Counter.builder("reservation.reconciler.runs").tag("outcome", "skipped").register(meters);
        this.corrections = Counter.builder("reservation.reconciler.corrections").register(meters);
        this.staleRejected = Counter.builder("reservation.reconciler.stale_writes_rejected").register(meters);
    }

    /** One round. @return false if another instance holds the lock (nothing done). */
    public boolean reconcileOnce() {
        var lease = lock.tryAcquire(LOCK_NAME, lockTtl);
        if (lease.isEmpty()) {
            skipped.increment();
            return false;
        }
        acquired.increment();
        try {
            Map<Long, Long> targets = readTargets();
            targets.forEach((eventId, dbRemaining) -> write(lease.get(), eventId, dbRemaining));
            return true;
        } finally {
            lock.release(lease.get()); // compare-and-delete: never frees someone else's lock
        }
    }

    /** DB remaining stock for every event that currently has a Redis gate counter. */
    public Map<Long, Long> readTargets() {
        List<Long> eventIds = new ArrayList<>();
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match("stock:*").count(500).build())) {
            keys.forEachRemaining(k -> eventIds.add(Long.parseLong(k.substring("stock:".length()))));
        }
        Map<Long, Long> targets = new LinkedHashMap<>();
        if (eventIds.isEmpty()) {
            return targets;
        }
        jdbc.sql("""
                        SELECT event_id, COALESCE(SUM(qty), 0) AS remaining
                        FROM stock WHERE event_id = ANY(:ids) GROUP BY event_id ORDER BY event_id
                        """)
                .param("ids", eventIds.toArray(Long[]::new))
                .query((rs, n) -> Map.entry(rs.getLong("event_id"), rs.getLong("remaining")))
                .list()
                .forEach(e -> targets.put(e.getKey(), e.getValue()));
        return targets;
    }

    /** Fenced write of one counter. Public so tests can drive the paused-owner scenario step by step. */
    public WriteResult write(Lease lease, long eventId, long value) {
        long r = redis.execute(FENCED_SET,
                List.of(RedisLock.fenceKey(lease.name()), RedisGateStrategy.stockKey(eventId)),
                Long.toString(lease.fenceToken()), Long.toString(value));
        WriteResult result = switch ((int) r) {
            case 1 -> WriteResult.CORRECTED;
            case 0 -> WriteResult.UNCHANGED;
            case -2 -> WriteResult.COUNTER_GONE;
            default -> WriteResult.REJECTED_STALE_TOKEN;
        };
        if (result == WriteResult.CORRECTED) {
            corrections.increment();
            log.info("Redis gate for event {} corrected to {} (token {})", eventId, value, lease.fenceToken());
        } else if (result == WriteResult.REJECTED_STALE_TOKEN) {
            staleRejected.increment();
            log.warn("Rejected stale write for event {}: token {} is no longer current", eventId, lease.fenceToken());
        }
        return result;
    }
}
