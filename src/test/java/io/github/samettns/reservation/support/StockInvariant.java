package io.github.samettns.reservation.support;

import io.github.samettns.reservation.domain.StockSnapshot;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's core correctness rules, in one reusable place. Every later stage
 * (sharding, TTL, outbox, fault injection) ends its tests by calling these.
 *
 * <ol>
 *   <li>Conservation: held + confirmed + remaining = total (nothing created or lost;
 *       an EXPIRED reservation gave its unit back, so it is not counted)</li>
 *   <li>No overselling: held + confirmed ≤ total</li>
 *   <li>No shard below zero; the shards add up to {@code remaining}</li>
 *   <li>(optional) The DB agrees with what clients were told: held + confirmed = successful responses</li>
 * </ol>
 *
 * <p>Registered as a bean via {@code @Import} on {@link io.github.samettns.reservation.IntegrationTest}.
 */
public class StockInvariant {

    private final EventRepository events;
    private final StringRedisTemplate redis;

    public StockInvariant(EventRepository events, StringRedisTemplate redis) {
        this.events = events;
        this.redis = redis;
    }

    public StockSnapshot snapshot(long eventId) {
        return events.findStock(eventId).orElseThrow();
    }

    /** Rules 1-3: valid at any moment once traffic has stopped, whatever happened before. */
    public StockSnapshot assertConsistent(long eventId) {
        StockSnapshot s = snapshot(eventId);
        assertThat(s.reserved())
                .as("reserved %d != held %d + confirmed %d", s.reserved(), s.held(), s.confirmed())
                .isEqualTo(s.held() + s.confirmed());
        assertThat(s.reserved())
                .as("overselling: held + confirmed %d > total %d", s.reserved(), s.totalStock())
                .isLessThanOrEqualTo(s.totalStock());
        assertThat(s.held() + s.confirmed() + s.remaining())
                .as("conservation broken: held %d + confirmed %d + remaining %d != total %d (expired %d)",
                        s.held(), s.confirmed(), s.remaining(), s.totalStock(), s.expired())
                .isEqualTo(s.totalStock());
        List<Integer> shards = events.shardQuantities(eventId);
        assertThat(shards).as("no shard below zero").allMatch(q -> q >= 0);
        assertThat(shards.stream().mapToInt(Integer::intValue).sum()).isEqualTo(s.remaining());
        return s;
    }

    /** Rules 1-4. {@code successfulReservations} = responses that said "you got it" (none expired yet). */
    public StockSnapshot assertHolds(long eventId, long successfulReservations) {
        StockSnapshot s = assertConsistent(eventId);
        assertThat(s.reserved())
                .as("DB active reservations (%d) != successful responses (%d)", s.reserved(), successfulReservations)
                .isEqualTo(successfulReservations);
        return s;
    }

    /** For the Redis strategy: once traffic has stopped, the gate counter must equal the DB. */
    public void assertRedisMatchesDatabase(long eventId) {
        String value = redis.opsForValue().get(RedisGateStrategy.stockKey(eventId));
        assertThat(value).as("redis counter for event %d", eventId).isNotNull();
        assertThat(Integer.parseInt(value))
                .as("redis counter vs DB remaining")
                .isEqualTo(snapshot(eventId).remaining());
    }
}
