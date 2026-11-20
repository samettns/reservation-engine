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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings every Redis gate counter back to the database's real remaining stock.
 *
 * <p>Why drift happens: the gate is decremented in Redis before the DB transaction, and given back
 * in after-commit/after-rollback callbacks. If the process dies in between, the callback never
 * runs and Redis stays LOWER than the DB: units that exist can no longer be sold.
 *
 * <p>Runs on every instance on a schedule, but a {@link RedisLock} lets only one of them do the
 * work per round. Every write to a counter is FENCED: a Lua script compares the writer's token with
 * the newest token issued; a writer whose lock expired and was re-acquired by someone else is
 * rejected atomically, inside Redis, even if it does not know it lost the lock.
 *
 * <p><b>Races with live traffic while holding the lock legitimately.</b> The round reads the DB at
 * time t<sub>r</sub> and writes Redis at t<sub>w</sub>; other instances keep working in between.
 * <ul>
 *   <li>A reservation decrements Redis BEFORE its DB commit. If its decrement lands before
 *       t<sub>w</sub> but its commit after t<sub>r</sub>, our write erases the decrement: Redis ends
 *       HIGHER than the DB. Safe direction (the DB has the final say); the surplus is either
 *       consumed by requests the DB turns away, or removed by the next round.</li>
 *   <li>A stock return increments Redis AFTER its DB commit. If it commits after t<sub>r</sub> and
 *       increments before t<sub>w</sub>, our write erases the increment: Redis ends LOWER than the
 *       DB, i.e. false "sold out" answers until the next round. This is the only way a round can
 *       create under-reporting, and it needs a write that LOWERS the counter.</li>
 * </ul>
 * Therefore: a write that raises the counter is always allowed (it can only reduce
 * under-reporting); a write that lowers it is allowed only if the event's returns version is
 * unchanged since before the DB read, i.e. no return slipped in. Otherwise it is skipped and the
 * next round retries.
 */
@Component
public class RedisStockReconciler {

    private static final Logger log = LoggerFactory.getLogger(RedisStockReconciler.class);

    public static final String LOCK_NAME = "redis-stock-reconciler";

    /** Result of one fenced write. */
    public enum WriteResult { CORRECTED, UNCHANGED, COUNTER_GONE, REJECTED_STALE_TOKEN, SKIPPED_RETURN_IN_FLIGHT }

    /** What a round read for one event: the returns version, then (after it) the DB remaining stock. */
    public record Target(long dbRemaining, String returnsVersion) {
    }

    /**
     * KEYS[1] = fence counter, KEYS[2] = gate counter, KEYS[3] = returns version;
     * ARGV[1] = writer's token, ARGV[2] = target value, ARGV[3] = returns version seen before the DB read.
     * Atomic in Redis: no newer lock acquisition and no stock return can slip in between the checks
     * and the write.
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
            if tonumber(ARGV[2]) < tonumber(current)
                    and (redis.call('GET', KEYS[3]) or '0') ~= ARGV[3] then
                return -3
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
    private final Counter returnRaces;

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
        this.returnRaces = Counter.builder("reservation.reconciler.lowering_skipped").register(meters);
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
            readTargets().forEach((eventId, target) -> write(lease.get(), eventId, target));
            return true;
        } finally {
            lock.release(lease.get()); // compare-and-delete: never frees someone else's lock
        }
    }

    /** For every event that currently has a Redis gate counter: its returns version, THEN its DB stock. */
    public Map<Long, Target> readTargets() {
        List<Long> eventIds = new ArrayList<>();
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions().match("stock:*").count(500).build())) {
            keys.forEachRemaining(k -> eventIds.add(Long.parseLong(k.substring("stock:".length()))));
        }
        Map<Long, Target> targets = new LinkedHashMap<>();
        if (eventIds.isEmpty()) {
            return targets;
        }
        // Order matters: versions first. A return that commits after the DB read bumps its version
        // after this point, so the write sees the change.
        List<String> versions = redis.opsForValue().multiGet(
                eventIds.stream().map(RedisGateStrategy::returnsVersionKey).toList());
        Map<Long, String> versionByEvent = new HashMap<>();
        for (int i = 0; i < eventIds.size(); i++) {
            String v = versions == null ? null : versions.get(i);
            versionByEvent.put(eventIds.get(i), v == null ? "0" : v);
        }
        jdbc.sql("""
                        SELECT event_id, COALESCE(SUM(qty), 0) AS remaining
                        FROM stock WHERE event_id = ANY(:ids) GROUP BY event_id ORDER BY event_id
                        """)
                .param("ids", eventIds.toArray(Long[]::new))
                .query((rs, n) -> Map.entry(rs.getLong("event_id"), rs.getLong("remaining")))
                .list()
                .forEach(e -> targets.put(e.getKey(), new Target(e.getValue(), versionByEvent.get(e.getKey()))));
        return targets;
    }

    /** Fenced, return-aware write of one counter. Public so tests can drive the races step by step. */
    public WriteResult write(Lease lease, long eventId, Target target) {
        long r = redis.execute(FENCED_SET,
                List.of(RedisLock.fenceKey(lease.name()), RedisGateStrategy.stockKey(eventId),
                        RedisGateStrategy.returnsVersionKey(eventId)),
                Long.toString(lease.fenceToken()), Long.toString(target.dbRemaining()), target.returnsVersion());
        WriteResult result = switch ((int) r) {
            case 1 -> WriteResult.CORRECTED;
            case 0 -> WriteResult.UNCHANGED;
            case -2 -> WriteResult.COUNTER_GONE;
            case -3 -> WriteResult.SKIPPED_RETURN_IN_FLIGHT;
            default -> WriteResult.REJECTED_STALE_TOKEN;
        };
        switch (result) {
            case CORRECTED -> {
                corrections.increment();
                log.info("Redis gate for event {} corrected to {} (token {})", eventId, target.dbRemaining(),
                        lease.fenceToken());
            }
            case REJECTED_STALE_TOKEN -> {
                staleRejected.increment();
                log.warn("Rejected stale write for event {}: token {} is no longer current", eventId,
                        lease.fenceToken());
            }
            case SKIPPED_RETURN_IN_FLIGHT -> {
                returnRaces.increment();
                log.info("Not lowering the Redis gate for event {}: a stock return raced this round", eventId);
            }
            default -> { }
        }
        return result;
    }
}
