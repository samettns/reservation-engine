package io.github.samettns.reservation.support;

import io.github.samettns.reservation.domain.StockSnapshot;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The project's core correctness rules, in one reusable place. Every later stage
 * (sharding, TTL, outbox, fault injection) ends its tests by calling these.
 *
 * <ol>
 *   <li>Conservation: reserved + remaining = total (nothing created or lost)</li>
 *   <li>No overselling: reserved ≤ total</li>
 *   <li>The DB agrees with what clients were told: reserved = number of successful responses</li>
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

    /** Asserts all three rules. {@code successfulReservations} = responses that said "you got it". */
    public StockSnapshot assertHolds(long eventId, long successfulReservations) {
        StockSnapshot s = snapshot(eventId);
        assertThat(s.reserved())
                .as("overselling: reserved %d > total %d", s.reserved(), s.totalStock())
                .isLessThanOrEqualTo(s.totalStock());
        assertThat(s.reserved() + s.remaining())
                .as("conservation broken: reserved %d + remaining %d != total %d",
                        s.reserved(), s.remaining(), s.totalStock())
                .isEqualTo(s.totalStock());
        assertThat(s.reserved())
                .as("DB rows (%d) != successful responses (%d)", s.reserved(), successfulReservations)
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
