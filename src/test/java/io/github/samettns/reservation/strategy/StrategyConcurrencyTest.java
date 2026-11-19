package io.github.samettns.reservation.strategy;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.domain.StockSnapshot;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.ConcurrentRunner.Attempt;
import io.github.samettns.reservation.support.StockInvariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

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
    private static final List<String> CORRECT_STRATEGIES = List.of("atomic", "optimistic", "pessimistic", "redis");

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    StockInvariant invariant;
    @Autowired
    JdbcClient jdbc;

    static Stream<Arguments> correctStrategiesAndStock() {
        return CORRECT_STRATEGIES.stream()
                .flatMap(name -> Stream.of(Arguments.of(name, 1), Arguments.of(name, 100)));
    }

    @ParameterizedTest(name = "{0}: stock={1}, 1000 concurrent requests -> no overselling")
    @MethodSource("correctStrategiesAndStock")
    void correctStrategiesNeverOversell(String strategyName, int stock) {
        long eventId = events.create("concurrency-" + strategyName, stock);
        ReservationService service = serviceWith(strategyName);

        Tally tally = reserveConcurrently(service, eventId, REQUESTS);
        System.out.printf("[%s stock=%d] %s%n", strategyName, stock, tally);

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
    }

    /**
     * A race condition is probabilistic: two transactions must read the stock inside the
     * ~1 ms window before the first one commits. Measured: with stock=1 a single burst
     * misses it roughly half the time (it failed once in a clean CI run). So the burst is
     * repeated on a fresh event up to {@value #NAIVE_MAX_ROUNDS} times and at least one
     * round must oversell (false failure odds ~0.5^20, about one in a million).
     * That this is needed at all is the lesson: races slip through "it worked once" tests.
     */
    private static final int NAIVE_MAX_ROUNDS = 20;

    @ParameterizedTest(name = "naive: stock={0}, 1000 concurrent requests -> OVERSELLS")
    @ValueSource(ints = {1, 100})
    void naiveStrategyOversells(int stock) {
        ReservationService service = serviceWith("naive");

        StockSnapshot oversold = null;
        for (int round = 1; round <= NAIVE_MAX_ROUNDS && oversold == null; round++) {
            long eventId = events.create("concurrency-naive", stock);
            Tally tally = reserveConcurrently(service, eventId, REQUESTS);
            StockSnapshot s = invariant.snapshot(eventId);
            System.out.printf("[naive stock=%d round=%d] %s -> reserved=%d remaining=%d%n",
                    stock, round, tally, s.reserved(), s.remaining());
            assertThat(tally.unexpectedErrors()).isEmpty();

            // Lost updates: more reservations exist than units that left the stock counter.
            if (s.reserved() + s.remaining() > s.totalStock()) {
                oversold = s;
            }
        }

        assertThat(oversold)
                .as("naive strategy should break conservation within %d rounds", NAIVE_MAX_ROUNDS)
                .isNotNull();
        assertThat(oversold.reserved()).as("more sold than exists").isGreaterThan(stock);
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
        var oneShot = new OptimisticLockingStrategy(jdbc, 1);
        ReservationService service = new ReservationService(oneShot, reservations, events, tx);

        Tally tally = reserveConcurrently(service, eventId, 200);
        System.out.printf("[optimistic max-attempts=1] %s%n", tally);

        assertThat(tally.unexpectedErrors()).isEmpty();
        assertThat(tally.contended()).as("some requests should lose the race and give up").isPositive();
        invariant.assertHolds(eventId, tally.created());
    }

    // --- helpers -------------------------------------------------------------

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
        return new ReservationService(strategy, reservations, events, tx);
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
