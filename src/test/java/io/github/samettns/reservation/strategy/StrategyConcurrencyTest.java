package io.github.samettns.reservation.strategy;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.domain.StockSnapshot;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.ConcurrentRunner.Attempt;
import io.github.samettns.reservation.support.StockInvariant;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1,000 concurrent reservation attempts per scenario, against real PostgreSQL and Redis.
 * Calls the service layer directly (not HTTP) so the test isolates the strategy and is
 * not limited by the embedded web server or HTTP client.
 */
class StrategyConcurrencyTest extends IntegrationTest {

    private static final int REQUESTS = 1_000;
    private static final List<String> CORRECT_STRATEGIES =
            List.of("atomic", "optimistic", "pessimistic", "pessimistic-precheck", "redis");

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    StockShards stockShards;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    ReservationEvents reservationEvents;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    StockInvariant invariant;
    @Autowired
    JdbcClient jdbc;

    /** Every correct strategy x stock {1, 100} x shards {1, 8}. stock=1 with 8 shards leaves 7 shards empty. */
    static Stream<Arguments> correctStrategiesAndStock() {
        return CORRECT_STRATEGIES.stream().flatMap(name -> Stream.of(
                Arguments.of(name, 1, 1), Arguments.of(name, 100, 1),
                Arguments.of(name, 1, 8), Arguments.of(name, 100, 8)));
    }

    @ParameterizedTest(name = "{0}: stock={1}, shards={2}, 1000 concurrent requests -> no overselling")
    @MethodSource("correctStrategiesAndStock")
    void correctStrategiesNeverOversell(String strategyName, int stock, int shards) {
        long eventId = events.create("concurrency-" + strategyName, stock, shards);
        ReservationService service = serviceWith(strategyName);

        Tally tally = reserveConcurrently(service, eventId, REQUESTS);
        System.out.printf("[%s stock=%d shards=%d] %s%n", strategyName, stock, shards, tally);

        assertThat(tally.unexpectedErrors()).as("unexpected exceptions").isEmpty();
        StockSnapshot snapshot = invariant.assertHolds(eventId, tally.created());

        if (strategyName.equals("optimistic")) {
            // Requests can give up after max attempts (503), so not every unit is
            // guaranteed to be sold in one burst. Still: never more than the stock.
            assertThat(tally.created() + tally.contended()).isLessThanOrEqualTo(REQUESTS);
        } else {
            // Demand (1000) exceeds supply, so every unit must be sold and the rest refused.
            assertThat(snapshot.reserved()).isEqualTo(stock);
            assertThat(tally.soldOut()).isEqualTo(REQUESTS - stock);
        }
        if (strategyName.equals("redis")) {
            invariant.assertRedisMatchesDatabase(eventId);
        }
        if (!strategyName.equals("optimistic")) {
            assertThat(events.shardQuantities(eventId)).as("every shard drained").containsOnly(0);
        }
    }

    /**
     * The sharding trade-off to guard against: a request lands on an empty shard while other
     * shards still have stock. It must move on, not answer "sold out". Requests are sequential
     * so every refusal before the end would be a false "sold out".
     */
    @ParameterizedTest(name = "{0}: 8 shards, unevenly drained -> never 'sold out' while stock remains")
    @MethodSource("correctStrategyNames")
    void shardedEventNeverAnswersSoldOutWhileStockRemains(String strategyName) {
        long eventId = events.create("sharded-no-false-sold-out-" + strategyName, 10, 8); // shards: 2,2,1,1,1,1,1,1
        ReservationService service = serviceWith(strategyName);

        for (int i = 0; i < 10; i++) {
            ReservationOutcome outcome = service.reserve(eventId, "user-" + i, "event-" + eventId + "-key-" + i);
            assertThat(outcome).as("request %d of 10", i + 1).isInstanceOf(ReservationOutcome.Created.class);
        }
        assertThat(service.reserve(eventId, "late", "event-" + eventId + "-late"))
                .isInstanceOf(ReservationOutcome.SoldOut.class);
        assertThat(events.shardQuantities(eventId)).containsOnly(0);
        invariant.assertHolds(eventId, 10);
    }

    /**
     * Regression: the shard count used to be cached in memory per event id. When ids were reused
     * (the benchmark resets the DB with RESTART IDENTITY) a 16-shard event was treated as
     * single-shard, and every request got "sold out" after one shard was empty. Here the layout
     * of the SAME event id changes in the database; the next reservation must see it.
     */
    @Test
    void shardLayoutIsNeverServedFromAStaleCache() {
        long eventId = events.create("layout-change", 1, 1);
        ReservationService service = serviceWith("atomic");
        assertThat(service.reserve(eventId, "first", "event-" + eventId + "-first"))
                .isInstanceOf(ReservationOutcome.Created.class); // shard 0 is now empty

        jdbc.sql("UPDATE events SET shard_count = 2, total_stock = 2 WHERE id = :id").param("id", eventId).update();
        jdbc.sql("INSERT INTO stock (event_id, shard_no, qty) VALUES (:id, 1, 1)").param("id", eventId).update();

        assertThat(service.reserve(eventId, "second", "event-" + eventId + "-second"))
                .as("the unit on the new shard must be found")
                .isInstanceOf(ReservationOutcome.Created.class);
        invariant.assertHolds(eventId, 2);
    }

    @Test
    void stockIsSplitAsEvenlyAsPossibleAcrossShards() {
        assertThat(events.shardQuantities(events.create("split", 10, 4))).containsExactly(3, 3, 2, 2);
        assertThat(events.shardQuantities(events.create("split", 1, 4))).containsExactly(1, 0, 0, 0);
        assertThat(events.shardQuantities(events.create("split", 100, 1))).containsExactly(100);
    }

    static Stream<String> correctStrategyNames() {
        return CORRECT_STRATEGIES.stream();
    }

    /**
     * Without help, the race is probabilistic: two transactions must read inside the ~1 ms
     * between the first one's SELECT and commit. Measured: at stock=1 a single burst missed
     * it about half the time. The test-only race window (a pause between read and write)
     * makes it deterministic: every transaction that gets a connection reads before anyone
     * writes, so with a 20-connection pool ~20 transactions read the same value every time.
     * 50 ms is orders of magnitude wider than the gap between those reads.
     */
    private static final Duration NAIVE_RACE_WINDOW = Duration.ofMillis(50);

    @ParameterizedTest(name = "naive: stock={0}, 1000 concurrent requests -> OVERSELLS")
    @ValueSource(ints = {1, 100})
    void naiveStrategyOversells(int stock) {
        long eventId = events.create("concurrency-naive", stock);
        var naive = new NaiveStrategy(jdbc, stockShards, NAIVE_RACE_WINDOW);
        ReservationService service = new ReservationService(naive, reservations, events, reservationEvents, tx);

        Tally tally = reserveConcurrently(service, eventId, REQUESTS);
        StockSnapshot s = invariant.snapshot(eventId);
        System.out.printf("[naive stock=%d] %s -> reserved=%d remaining=%d%n",
                stock, tally, s.reserved(), s.remaining());

        assertThat(tally.unexpectedErrors()).isEmpty();
        // Lost updates: more reservations exist than units that left the stock counter.
        assertThat(s.reserved() + s.remaining())
                .as("naive strategy must break conservation (lost updates)")
                .isGreaterThan(s.totalStock());
        assertThat(s.reserved()).as("more sold than exists").isGreaterThan(stock);
    }

    @Test
    void productionNaiveBeanHasNoRaceWindow() throws Exception {
        NaiveStrategy bean = (NaiveStrategy) strategies.stream()
                .filter(s -> s.name().equals("naive")).findFirst().orElseThrow();
        var field = NaiveStrategy.class.getDeclaredField("raceWindow");
        field.setAccessible(true);
        assertThat((Duration) field.get(bean)).isZero();
    }

    @ParameterizedTest(name = "{0}: stock={1}, 100 concurrent retries of the SAME request -> exactly one reservation")
    @MethodSource("correctStrategiesAndSmallStock")
    void concurrentRetriesWithSameIdempotencyKeyReserveOnce(String strategyName, int stock) {
        long eventId = events.create("idempotency-" + strategyName, stock);
        ReservationService service = serviceWith(strategyName);

        String sameKey = "same-key-for-event-" + eventId;
        Tally tally = Tally.of(ConcurrentRunner.run(100,
                i -> service.reserve(eventId, "alice", sameKey)));
        System.out.printf("[%s same-key stock=%d] %s%n", strategyName, stock, tally);

        assertThat(tally.unexpectedErrors()).isEmpty();
        assertThat(tally.created()).as("exactly one reservation for one logical request").isEqualTo(1);
        StockSnapshot s = invariant.assertHolds(eventId, 1);
        assertThat(s.remaining()).isEqualTo(stock - 1);

        if (strategyName.equals("redis")) {
            // Losers' Redis decrements must have been given back by the rollback callback.
            invariant.assertRedisMatchesDatabase(eventId);
            // KNOWN GAP (closed in stage 5 with in-progress idempotency records): while the
            // winner is still in flight, its twins' pending Redis decrements can make the
            // gate read 0, so some twins answer "sold out" for a request that succeeded.
            // Safe for stock (no overselling) but a wrong answer to the client.
            assertThat(tally.created() + tally.replayed() + tally.soldOut()).isEqualTo(100);
        } else {
            // DB strategies: twins wait on the row lock until the winner commits, then the
            // sold-out re-check finds the winner. Every twin must get the original result.
            assertThat(tally.soldOut()).as("twins told 'sold out' although their request succeeded").isZero();
            assertThat(tally.replayed()).isEqualTo(99);
        }
    }

    static Stream<Arguments> correctStrategiesAndSmallStock() {
        return CORRECT_STRATEGIES.stream()
                .flatMap(name -> Stream.of(Arguments.of(name, 1), Arguments.of(name, 10)));
    }

    @Test
    void optimisticStrategyGivesUpWithContentionExceptionWhenAttemptsRunOut() {
        long eventId = events.create("optimistic-exhaustion", 1_000);
        // One attempt only: anyone who loses the first race must give up.
        var meters = new SimpleMeterRegistry();
        var oneShot = new OptimisticLockingStrategy(jdbc, stockShards, 1, meters);
        ReservationService service = new ReservationService(oneShot, reservations, events, reservationEvents, tx);

        Tally tally = reserveConcurrently(service, eventId, 200);
        System.out.printf("[optimistic max-attempts=1] %s%n", tally);

        assertThat(tally.unexpectedErrors()).isEmpty();
        // The benchmark relies on these metrics: they must match what clients saw.
        assertThat(attempts(meters, "acquired").count()).isEqualTo(tally.created());
        assertThat(attempts(meters, "exhausted").count()).isEqualTo(tally.contended());
        assertThat(tally.contended()).as("some requests should lose the race and give up").isPositive();
        invariant.assertHolds(eventId, tally.created());
    }

    @Test
    void optimisticAttemptsMetricCountsRetries() {
        long eventId = events.create("optimistic-metrics", 1_000_000);
        var meters = new SimpleMeterRegistry();
        var strategy = new OptimisticLockingStrategy(jdbc, stockShards, 10, meters);
        ReservationService service = new ReservationService(strategy, reservations, events, reservationEvents, tx);

        Tally tally = reserveConcurrently(service, eventId, 500);
        DistributionSummary acquired = attempts(meters, "acquired");
        System.out.printf("[optimistic metrics] %s avgAttempts=%.2f maxAttempts=%.0f%n",
                tally, acquired.mean(), acquired.max());

        assertThat(acquired.count() + attempts(meters, "exhausted").count())
                .isEqualTo(tally.created() + tally.contended());
        assertThat(acquired.mean()).as("contention should force some retries").isGreaterThan(1.0);
        assertThat(acquired.max()).isBetween(2.0, 10.0);
    }

    // --- helpers -------------------------------------------------------------

    private static DistributionSummary attempts(SimpleMeterRegistry meters, String outcome) {
        return meters.get(OptimisticLockingStrategy.ATTEMPTS_METRIC).tag("outcome", outcome).summary();
    }

    /**
     * {@code count} different users, each with its own key. Keys include the event id
     * because the same (user, key) pair used for another event is correctly rejected
     * with 422; test runs must not leak keys into each other.
     */
    private static Tally reserveConcurrently(ReservationService service, long eventId, int count) {
        List<Attempt<ReservationOutcome>> attempts = ConcurrentRunner.run(count,
                i -> service.reserve(eventId, "user-" + i, "event-" + eventId + "-key-" + i));
        return Tally.of(attempts);
    }

    /** Same wiring as production, but with an explicitly chosen strategy. */
    private ReservationService serviceWith(String strategyName) {
        ReservationStrategy strategy = strategies.stream()
                .filter(s -> s.name().equals(strategyName))
                .findFirst()
                .orElseThrow();
        return new ReservationService(strategy, reservations, events, reservationEvents, tx);
    }

    /** Counts of each outcome over a run. */
    record Tally(long created, long replayed, long soldOut, long contended, List<Throwable> unexpectedErrors) {

        static Tally of(List<Attempt<ReservationOutcome>> attempts) {
            long created = 0, replayed = 0, soldOut = 0, contended = 0;
            List<Throwable> unexpected = new ArrayList<>();
            for (Attempt<ReservationOutcome> a : attempts) {
                if (a.failed()) {
                    if (a.error() instanceof ContentionException) {
                        contended++;
                    } else {
                        unexpected.add(a.error());
                    }
                    continue;
                }
                switch (a.value()) {
                    case ReservationOutcome.Created c -> created++;
                    case ReservationOutcome.Replayed r -> replayed++;
                    case ReservationOutcome.SoldOut s -> soldOut++;
                }
            }
            return new Tally(created, replayed, soldOut, contended, unexpected);
        }

        @Override
        public String toString() {
            return "created=%d replayed=%d soldOut=%d contended=%d errors=%d"
                    .formatted(created, replayed, soldOut, contended, unexpectedErrors.size());
        }
    }
}
