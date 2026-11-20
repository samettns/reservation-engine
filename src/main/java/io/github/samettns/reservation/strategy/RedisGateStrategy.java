package io.github.samettns.reservation.strategy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis as an admission gate in front of the database.
 *
 * <p>A Lua script checks and decrements a counter atomically in Redis (Redis runs a
 * script without interleaving other commands). Only requests that pass the gate touch
 * PostgreSQL, where the {@link AtomicUpdateStrategy} UPDATE is still executed. So when
 * 5,000 people chase 100 tickets, ~4,900 are turned away in Redis without ever taking a
 * DB connection or contending for the row lock.
 *
 * <p><b>Source of truth is PostgreSQL.</b> Redis is a cache-like gate. HIGHER than the DB is safe
 * for correctness (the DB UPDATE has the final say; it only costs DB work). LOWER than the DB never
 * oversells, but is harmful: existing units are refused, and at 0 the "sold out" shortcut answers
 * wrongly. Every writer that sets the counter from a DB read guards against creating that
 * direction (see the returns version). Drift cases:
 * <ul>
 *   <li>DB transaction rolls back after Redis was decremented: compensated with INCR in an
 *       after-completion callback.</li>
 *   <li>Process crashes between DECR and commit: the callback never runs, Redis stays one
 *       lower than the DB. Fixed by the reconciliation job (stage 6).</li>
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

    /**
     * Stock RETURN: like {@link #INCREMENT_IF_PRESENT}, and always bumps the event's returns version
     * (KEYS[2]) in the same atomic step. Writers that set the counter from a DB read (the reconciler,
     * lazy initialisation) compare that version to detect a return that committed after their read:
     * overwriting it would hide the returned units (Redis LOWER than the DB).
     */
    private static final RedisScript<Long> RETURN_UNITS = RedisScript.of("""
            redis.call('INCR', KEYS[2])
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    /**
     * Lazy initialisation: create the counter from a DB read only if it is still missing AND no stock
     * return happened since before the read. 1 = created, 0 = someone else created it first,
     * -1 = a return raced the read (counter left missing; the DB decides this request).
     */
    private static final RedisScript<Long> INITIALISE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
            end
            if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[2] then
                return -1
            end
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    /**
     * Set (by whichever instance notices) when Redis worked again after failures: while Redis was
     * unreachable the DB kept changing, so counters may be stale - possibly LOWER than the DB (a
     * stock return whose INCR failed). While this key exists, no instance trusts a counter at 0;
     * the reconciler deletes it after a successful round. It lives in Redis, not in the instance,
     * because the stale counters are shared by all instances.
     */
    public static final String STALE_KEY = "gate:stale";

    private final StringRedisTemplate redis;
    private final JdbcClient jdbc;
    private final AtomicUpdateStrategy database;
    private final ApplicationEventPublisher publisher;
    private final CircuitBreaker breaker;
    private final Counter bypassed;
    private final Counter shortCircuited;
    private final Counter gateSoldOut;
    private final Counter gateSoldOutButDbHadStock;
    private final boolean verifySoldOutAlways;
    /** A Redis call failed or was skipped since the last success: counters may be stale. */
    private volatile boolean countersMayBeStale;

    @Autowired
    public RedisGateStrategy(StringRedisTemplate redis, JdbcClient jdbc, AtomicUpdateStrategy database,
                             MeterRegistry meters, ApplicationEventPublisher publisher,
                             @Value("${reservation.redis-gate.breaker.failure-threshold:3}") int failureThreshold,
                             @Value("${reservation.redis-gate.breaker.open-duration:PT2S}") Duration openDuration,
                             @Value("${reservation.redis-gate.verify-sold-out:always}") String verifySoldOut) {
        this.verifySoldOutAlways = !"stale-only".equals(verifySoldOut);
        this.gateSoldOut = Counter.builder("reservation.redis_gate.sold_out_at_gate").register(meters);
        this.gateSoldOutButDbHadStock = Counter.builder("reservation.redis_gate.sold_out_but_db_had_stock").register(meters);
        this.redis = redis;
        this.jdbc = jdbc;
        this.database = database;
        this.publisher = publisher;
        this.breaker = new CircuitBreaker(failureThreshold, openDuration);
        this.bypassed = Counter.builder("reservation.redis_gate.bypassed").register(meters);
        this.shortCircuited = Counter.builder("reservation.redis_gate.short_circuited").register(meters);
        Gauge.builder("reservation.redis_gate.breaker_open", breaker, b -> b.state() == CircuitBreaker.State.CLOSED ? 0 : 1)
                .register(meters);
    }

    /** Thrown inside this class when Redis is unreachable or the breaker is open. */
    private static final class GateUnavailable extends RuntimeException {
        GateUnavailable(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Every Redis call goes through here: the breaker decides whether to try at all (open: fail at
     * once, no 500 ms timeout); a failure counts against it and marks the counters as possibly stale;
     * the first success after that marks them stale in Redis and asks for an immediate reconciliation.
     */
    private <T> T redisCall(Supplier<T> call) {
        if (!breaker.tryAcquirePermission()) {
            shortCircuited.increment();
            countersMayBeStale = true;
            throw new GateUnavailable("circuit open");
        }
        T value;
        try {
            if (countersMayBeStale) {
                // FIRST mark the counters stale, THEN do the actual call: otherwise the first read
                // after the outage would still trust a stale 0 (found by RedisRecoveryTest).
                redis.opsForValue().set(STALE_KEY, UUID.randomUUID().toString());
                countersMayBeStale = false;
                publisher.publishEvent(new RedisGateRecovered());
                log.info("Redis reachable again; gate counters marked stale until the next reconciliation");
            }
            value = call.get();
        } catch (DataAccessException e) {
            breaker.onFailure();
            countersMayBeStale = true;
            throw new GateUnavailable(e.getMessage());
        }
        breaker.onSuccess();
        return value;
    }

    public CircuitBreaker.State breakerState() {
        return breaker.state();
    }

    /** Is the stale marker set? If Redis cannot say, assume yes (verify against the DB). */
    private boolean markedStale() {
        try {
            return Boolean.TRUE.equals(redisCall(() -> redis.hasKey(STALE_KEY)));
        } catch (GateUnavailable e) {
            return true;
        }
    }

    public static String stockKey(long eventId) {
        return "stock:" + eventId;
    }

    /** Bumped on every stock return. Deliberately outside the "stock:*" pattern the reconciler scans. */
    public static String returnsVersionKey(long eventId) {
        return "stock-returns:" + eventId;
    }

    /** Current returns version ("0" if none yet). Must be read BEFORE the DB stock it guards. */
    public String returnsVersion(long eventId) {
        String v = redis.opsForValue().get(returnsVersionKey(eventId));
        return v == null ? "0" : v;
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

        long result;
        try {
            result = redisCall(() -> runGate(key));
            if (result == NOT_INITIALISED) {
                // Lazy initialisation. Only the Redis calls go through the breaker: a failing DB read
                // here (e.g. pool timeout) is not a Redis failure and must not open the circuit.
                String versionBeforeRead = redisCall(() -> returnsVersion(eventId));
                int dbQty = dbRemaining(eventId);
                redisCall(() -> initialiseCounter(eventId, dbQty, versionBeforeRead));
                result = redisCall(() -> runGate(key));
            }
        } catch (GateUnavailable redisUnavailable) {
            // FAIL OPEN (stage 7b): Redis is only a gate in front of the DB, and the DB's atomic
            // UPDATE has the final say, so without Redis the request goes to the DB directly:
            // correct, just without the shortcut. (If the gate's DECR did run but its answer was
            // lost, Redis ends one LOWER than the DB; the reconciler's raise fixes that.)
            bypassed.increment();
            return database.tryAcquireOne(eventId);
        }
        if (result == SOLD_OUT) {
            // Reached only by requests that passed knownSoldOut (counter > 0 or marked stale) and
            // then found 0: race losers. The counter can be LOWER than the DB not only after an
            // outage (marked stale) but also after a process died between a stock return's DB
            // commit and its Redis INCR (nothing marks that). The caller writes "sold out" into the
            // DB's sold-out marker, which every strategy trusts and the reconciler does NOT repair;
            // so by default (verify-sold-out=always) the DB confirms first. stale-only exists to
            // measure what that costs (stage 8 benchmark).
            gateSoldOut.increment();
            if (!verifySoldOutAlways && !markedStale()) {
                return OptionalInt.empty();
            }
            OptionalInt fromDb = database.tryAcquireOne(eventId);
            if (fromDb.isPresent()) {
                gateSoldOutButDbHadStock.increment();
                log.warn("Redis gate of event {} said sold out but the DB had stock (stale counter)", eventId);
            }
            return fromDb;
        }
        if (result == NOT_INITIALISED) {
            // Initialisation was skipped (a stock return raced it). Nothing was decremented in
            // Redis, so nothing to compensate: the DB decides; the next request initialises.
            return database.tryAcquireOne(eventId);
        }

        // From here on Redis has been decremented: give the unit back if the DB transaction
        // does not commit (strategy fails below, reservation INSERT fails, duplicate key...).
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    // Only if the counter still exists: after a Redis restart a plain INCR would
                    // create it with value 1, far below the DB (a false "sold out").
                    try {
                        redisCall(() -> redis.execute(INCREMENT_IF_PRESENT, List.of(key), "1"));
                    } catch (GateUnavailable e) {
                        // Redis LOWER than the DB by one (safe for overselling); marked stale, the reconciler raises it.
                        log.warn("Could not give unit back to the Redis gate of event {}: {}", eventId, e.getMessage());
                    }
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
     * Redis "sold out" shortcut: the gate counter is at 0. One Redis round trip (MGET of the
     * counter and the stale marker), no database access. Becomes false again by itself when
     * expired holds return units ({@link #onStockReturned}). A missing counter is not "sold out"
     * (it is initialised from the DB on the normal path), and neither is a counter at 0 while
     * the counters are marked stale after an outage ({@link #STALE_KEY}).
     */
    @Override
    public boolean knownSoldOut(long eventId) {
        try {
            List<String> values = redisCall(() -> redis.opsForValue().multiGet(List.of(stockKey(eventId), STALE_KEY)));
            if (values == null || values.get(1) != null) {
                return false; // counters may be stale: a 0 could hide returned units -> ask the DB
            }
            return values.get(0) != null && Long.parseLong(values.get(0)) <= 0;
        } catch (GateUnavailable redisUnavailable) {
            return false; // fail open: "don't know" -> the normal path asks the DB
        }
    }

    /**
     * An expired hold gave units back to the DB: give them back to the gate too, otherwise Redis
     * keeps saying "sold out" while the DB has stock again. Applied only AFTER the DB commit (if
     * the return rolls back, Redis must not change). If the process dies between the commit and
     * this INCRBY, Redis stays lower than the DB until the next reconciliation round. A missing
     * key is left alone: lazy initialisation will read the already-returned stock from the DB.
     * The returns version is bumped either way (see {@link #RETURN_UNITS}).
     */
    @Override
    public void onStockReturned(long eventId, int units) {
        String key = stockKey(eventId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    redisCall(() -> redis.execute(RETURN_UNITS, List.of(key, returnsVersionKey(eventId)),
                            Integer.toString(units)));
                } catch (GateUnavailable e) {
                    // The DB return is committed; Redis stays LOWER. Marked stale: the "sold out"
                    // shortcut is not trusted until the reconciler (triggered on recovery) raises it.
                    log.warn("Could not mirror {} returned unit(s) of event {} into Redis: {}", units, eventId, e.getMessage());
                }
            }
        });
    }

    private long runGate(String key) {
        return redis.execute(TRY_DECREMENT, List.of(key));
    }

    /**
     * Lazy initialisation from the DB, safe against two races:
     * <ul>
     *   <li>many requests/instances initialising at once: only the first one creates the counter;</li>
     *   <li>a stock return committing between our DB read and our write: its INCRBY found no counter
     *       and did nothing, so writing our older, lower read would hide the returned units. The
     *       returns version, read BEFORE the DB, exposes that, and we skip.</li>
     * </ul>
     */
    private int dbRemaining(long eventId) {
        // One Redis counter per event, whatever the number of DB shards: the gate only needs
        // the total; the DB step (AtomicUpdateStrategy) picks the shard.
        return jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM stock WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Integer.class)
                .single();
    }

    /** The write half of initialisation. Public so tests can put a stock return between read and write. */
    public long initialiseCounter(long eventId, long dbQty, String versionBeforeRead) {
        return redis.execute(INITIALISE, List.of(stockKey(eventId), returnsVersionKey(eventId)),
                Long.toString(dbQty), versionBeforeRead);
    }
}
