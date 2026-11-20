package io.github.samettns.reservation.chaos;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.reconciliation.RedisStockReconciler;
import io.github.samettns.reservation.strategy.CircuitBreaker;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis goes away (the proxy refuses every connection) while the redis-gate strategy is selling.
 *
 * <p>Behaviour: the system WAITS a little, then serves from the DB. Each Redis call gives up after
 * the 500 ms command timeout; the gate then FAILS OPEN and the request goes straight to the DB's
 * atomic UPDATE, which has the final say anyway. Nothing is refused, nothing is oversold. When Redis
 * is back, its counter is stale (too high: the DB sold units Redis never saw); the reconciler
 * brings it back to the DB.
 */
class RedisOutageTest extends ChaosIntegrationTest {

    @Autowired
    List<ReservationStrategy> strategies;
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
    StockInvariant invariant;
    @Autowired
    RedisStockReconciler reconciler;
    @Autowired
    MeterRegistry meters;

    @Test
    void redisDownFallsBackToTheDatabaseWithoutOverselling() throws Exception {
        long eventId = events.create("redis-down", 20);
        ReservationService service = redisService();
        service.reserve(eventId, "warm", "rd-warm-" + eventId);           // gate counter exists: 19
        double bypassedBefore = meters.get("reservation.redis_gate.bypassed").counter().count();

        REDIS_PROXY.disable();
        long start = System.nanoTime();
        List<ConcurrentRunner.Attempt<ReservationOutcome>> attempts = ConcurrentRunner.run(50,
                i -> service.reserve(eventId, "u" + i, "rd-" + eventId + "-" + i));
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(attempts).as("every request answered, none failed").noneMatch(ConcurrentRunner.Attempt::failed);
        assertThat(tookMs).as("waits for the Redis timeout (500 ms), not Lettuce's 60 s default").isLessThan(10_000);
        long created = attempts.stream().filter(a -> a.value() instanceof ReservationOutcome.Created).count();
        long soldOut = attempts.stream().filter(a -> a.value() instanceof ReservationOutcome.SoldOut).count();
        assertThat(created).as("the 19 remaining units, served by the DB").isEqualTo(19);
        assertThat(soldOut).isEqualTo(31);
        invariant.assertHolds(eventId, 20);                              // 1 warm-up + 19
        assertThat(meters.get("reservation.redis_gate.bypassed").counter().count() - bypassedBefore)
                .as("the gate was bypassed").isPositive();
        System.out.printf("[redis down] 50 requests answered in %d ms (Redis timeout 500 ms per call)%n", tookMs);

        REDIS_PROXY.enable();
        await(() -> reconciler.reconcileOnce());                         // Redis reachable again
        invariant.assertRedisMatchesDatabase(eventId);                    // stale 19 -> real 0
    }

    /**
     * Redis HANGS (accepts the connection, never answers: a network black hole, a frozen process).
     * That is the expensive kind of outage: a refused connection fails within milliseconds (measured:
     * Lettuce gives up at once after the first attempt), but a hang costs the full 500 ms timeout
     * per call, twice per request (sold-out check + gate). The circuit breaker opens after 3
     * failures in a row and skips Redis at once; every 2 s one probe tries it again.
     */
    @Test
    void circuitBreakerStopsWaitingForAHangingRedis() throws Exception {
        long eventId = events.create("redis-breaker", 1_000);
        ReservationService service = redisService();
        RedisGateStrategy gate = (RedisGateStrategy) strategies.stream()
                .filter(s -> s.name().equals("redis")).findFirst().orElseThrow();
        service.reserve(eventId, "warm", "cb-warm-" + eventId);

        REDIS_PROXY.toxics().timeout("hang", ToxicDirection.UPSTREAM, 0);   // data goes nowhere, no answer
        long[] latencyMs = new long[30];
        for (int i = 0; i < latencyMs.length; i++) {                     // one after the other
            long t = System.nanoTime();
            assertThat(service.reserve(eventId, "u" + i, "cb-" + eventId + "-" + i))
                    .isInstanceOf(ReservationOutcome.Created.class);
            latencyMs[i] = (System.nanoTime() - t) / 1_000_000;
        }
        long[] sorted = latencyMs.clone();
        java.util.Arrays.sort(sorted);
        System.out.printf("[redis hangs, breaker] sequential latencies ms: %s%n", java.util.Arrays.toString(latencyMs));

        assertThat(gate.breakerState()).isNotEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(sorted[latencyMs.length / 2]).as("median: Redis skipped, no 500 ms wait").isLessThan(200);
        assertThat(java.util.Arrays.stream(latencyMs).sum()).as("30 requests, not 30 x 1 s").isLessThan(10_000);

        REDIS_PROXY.toxics().get("hang").remove();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> {
            service.reserve(eventId, "probe", "cb-probe-" + eventId + "-" + System.nanoTime());
            return gate.breakerState() == CircuitBreaker.State.CLOSED;     // a probe got through
        });
        invariant.assertConsistent(eventId);
    }

    private ReservationService redisService() {
        ReservationStrategy gate = strategies.stream().filter(s -> s.name().equals("redis")).findFirst().orElseThrow();
        return new ReservationService(gate, reservations, events, reservationEvents, idempotency, tx);
    }

    /** Retries until the call succeeds (Lettuce needs a moment to reconnect). */
    private static void await(java.util.function.BooleanSupplier call) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).ignoreExceptions().until(call::getAsBoolean);
    }
}
