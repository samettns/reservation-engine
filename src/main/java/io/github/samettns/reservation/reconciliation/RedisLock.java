package io.github.samettns.reservation.reconciliation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A lease-based lock on a SINGLE Redis node, plus a fencing token.
 *
 * <ul>
 *   <li>{@code SET lock:<name> <owner> NX PX <ttl>}: at most one owner at a time, and the lock
 *       frees itself if the owner dies.</li>
 *   <li>{@code INCR fence:<name>} after acquiring: a strictly increasing token per acquisition.</li>
 * </ul>
 *
 * <p><b>The lock alone is not safe.</b> An owner can be paused (GC, slow I/O) for longer than the
 * TTL; the lock expires, someone else acquires it, and the paused owner wakes up still believing it
 * holds the lock. Nothing in the lock can stop that. Safety therefore comes from the protected
 * resource checking the fencing token on every write (see {@link RedisStockReconciler}); the lock
 * only keeps instances from doing the same work at the same time. This is also why this is a
 * single-node lock and not Redlock: Redlock adds availability across nodes, but does not fix the
 * paused-owner problem, and it relies on bounded clock drift and process pauses for its safety.
 */
@Component
public class RedisLock {

    /** Owner = random id of this acquisition; fenceToken = its place in the global acquisition order. */
    public record Lease(String name, String owner, long fenceToken) {
    }

    /** Delete the lock only if we still own it (a stale owner must not free a lock someone else holds). */
    private static final RedisScript<Long> COMPARE_AND_DELETE = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public static String lockKey(String name) {
        return "lock:" + name;
    }

    public static String fenceKey(String name) {
        return "fence:" + name;
    }

    public Optional<Lease> tryAcquire(String name, Duration ttl) {
        String owner = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey(name), owner, ttl);
        if (!Boolean.TRUE.equals(acquired)) {
            return Optional.empty();
        }
        // Only lock holders increment, so tokens follow the acquisition order.
        long token = redis.opsForValue().increment(fenceKey(name));
        return Optional.of(new Lease(name, owner, token));
    }

    /** @return true if this call released the lock, false if it had expired or belongs to someone else now */
    public boolean release(Lease lease) {
        return redis.execute(COMPARE_AND_DELETE, List.of(lockKey(lease.name())), lease.owner()) == 1L;
    }
}
