package io.github.samettns.reservation.chaos;

import io.github.samettns.reservation.domain.HoldExpirySweeper;
import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis comes BACK after an outage. During the outage the DB kept changing without Redis seeing
 * it; the dangerous change is a stock RETURN (an expired hold): the DB gets the unit back, the
 * Redis INCR fails, so after the outage the Redis counter is LOWER than the DB. At 0 it would say
 * "sold out" for a unit that exists, and a request that reached the gate would even write that
 * lie into the DB as the sold-out marker, which every strategy trusts.
 */
class RedisRecoveryTest extends ChaosIntegrationTest {

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    List<StockReturnListener> stockReturnListeners;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    ReservationEvents reservationEvents;
    @Autowired
    IdempotencyRepository idempotency;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    StockInvariant invariant;
    @Autowired
    io.github.samettns.reservation.reconciliation.RedisStockReconciler reconciler;

    @Test
    void unitReturnedDuringTheOutageIsSellableRightAfterRedisComesBack() throws Exception {
        long eventId = events.create("redis-recovery", 1);
        ReservationService service = redisService();
        Reservation hold = ((ReservationOutcome.Created) service.reserve(eventId, "u0", "rec-" + eventId + "-0"))
                .reservation();                                                // DB 0, Redis 0

        REDIS_PROXY.disable();
        expire(hold);                                                          // DB 1, Redis INCR fails
        assertThat(dbRemaining(eventId)).isEqualTo(1);
        REDIS_PROXY.enable();
        await().atMost(Duration.ofSeconds(20)).ignoreExceptions()             // Redis reachable again,
                .until(() -> redis.opsForValue().get(RedisGateStrategy.stockKey(eventId)) != null);
        assertThat(redis.opsForValue().get(RedisGateStrategy.stockKey(eventId)))
                .as("stale: the return never reached Redis").isEqualTo("0");

        ReservationOutcome rightAfter = service.reserve(eventId, "u1", "rec-" + eventId + "-1");

        assertThat(rightAfter).as("the returned unit is sold, not refused as 'sold out'")
                .isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertConsistent(eventId);

        // The recovery triggered a reconciliation round by itself (no schedule runs in tests): it
        // clears the stale marker. That round can race the reservation above and leave Redis ONE
        // HIGHER than the DB - the safe direction (stage 7a analysis); never lower.
        // Up to the lock TTL (10 s) if a failed round right after the outage left its lock behind.
        await().atMost(Duration.ofSeconds(25)).until(() -> !Boolean.TRUE.equals(redis.hasKey(RedisGateStrategy.STALE_KEY)));
        assertThat(counter(eventId)).as("no under-reporting after the outage").isGreaterThanOrEqualTo(dbRemaining(eventId));
        await().atMost(Duration.ofSeconds(10)).until(reconciler::reconcileOnce);   // the next regular round
        invariant.assertRedisMatchesDatabase(eventId);
    }

    private long counter(long eventId) {
        return Long.parseLong(redis.opsForValue().get(RedisGateStrategy.stockKey(eventId)));
    }

    private void expire(Reservation hold) {
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = :id")
                .param("id", hold.id()).update();
        new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry()).sweepOnce();
    }

    private long dbRemaining(long eventId) {
        return jdbc.sql("SELECT COALESCE(SUM(qty), 0) FROM stock WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single();
    }

    private ReservationService redisService() {
        ReservationStrategy gate = strategies.stream().filter(s -> s.name().equals("redis")).findFirst().orElseThrow();
        return new ReservationService(gate, reservations, events, reservationEvents, idempotency, tx);
    }
}
