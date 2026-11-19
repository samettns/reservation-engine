package io.github.samettns.reservation.strategy;

import java.util.OptionalInt;
import io.github.samettns.reservation.strategy.StockShards.RowResult;
import io.github.samettns.reservation.strategy.StockShards.ShardRow;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.stream.IntStream;

/**
 * Read the row with its version, then write only if the version is unchanged
 * ("compare-and-set"). If someone else wrote first, 0 rows are updated and we retry
 * with a fresh read. This is what JPA's {@code @Version} does, written out by hand.
 *
 * <p>Retrying inside the same transaction is correct here because under READ COMMITTED
 * every statement sees the latest committed data, so the re-read is fresh.
 *
 * <p>Caveat specific to PostgreSQL: the conditional UPDATE still takes a row lock and a
 * loser waits for the winner to commit before seeing "0 rows". So "optimistic" here means
 * "no lock between read and write", not "never waits".
 *
 * <p>With sharding, attempts are counted per row: running out of attempts on one shard
 * moves on to the next shard; only if no shard works does the request fail with 503.
 */
@Component
public class OptimisticLockingStrategy implements ReservationStrategy {

    /** Number of compare-and-set attempts on a row, tagged by how that row attempt ended. */
    static final String ATTEMPTS_METRIC = "reservation.optimistic.attempts";

    private record VersionedStock(int qty, long version) {
    }

    private final JdbcClient jdbc;
    private final StockShards shards;
    private final int maxAttempts;
    private final DistributionSummary acquiredAttempts;
    private final DistributionSummary soldOutAttempts;
    private final DistributionSummary exhaustedAttempts;

    @Autowired
    public OptimisticLockingStrategy(JdbcClient jdbc,
                                     StockShards shards,
                                     @Value("${reservation.optimistic.max-attempts:10}") int maxAttempts,
                                     MeterRegistry meters) {
        this.jdbc = jdbc;
        this.shards = shards;
        this.maxAttempts = maxAttempts;
        this.acquiredAttempts = attemptsSummary(meters, "acquired");
        this.soldOutAttempts = attemptsSummary(meters, "sold_out");
        this.exhaustedAttempts = attemptsSummary(meters, "exhausted");
    }

    /**
     * One histogram bucket per attempt count (1, 2, ... max). Attempts are small integers,
     * so cumulative bucket counts give the exact distribution, and the benchmark can diff
     * them before/after a run to get that run's average and maximum.
     */
    private DistributionSummary attemptsSummary(MeterRegistry meters, String outcome) {
        double[] buckets = IntStream.rangeClosed(1, maxAttempts).asDoubleStream().toArray();
        return DistributionSummary.builder(ATTEMPTS_METRIC)
                .tag("outcome", outcome)
                .serviceLevelObjectives(buckets)
                .register(meters);
    }

    @Override
    public String name() {
        return "optimistic";
    }

    @Override
    public OptionalInt tryAcquireOne(long eventId) {
        return shards.acquire(eventId, this::tryRow);
    }

    private RowResult tryRow(ShardRow row) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            VersionedStock current = jdbc.sql("""
                            SELECT qty, version FROM stock WHERE event_id = :eventId AND shard_no = :shardNo
                            """)
                    .param("eventId", row.eventId())
                    .param("shardNo", row.shardNo())
                    .query((rs, rowNum) -> new VersionedStock(rs.getInt("qty"), rs.getLong("version")))
                    .single();
            if (current.qty() <= 0) {
                soldOutAttempts.record(attempt);
                return RowResult.EMPTY;
            }
            int updated = jdbc.sql("""
                            UPDATE stock
                            SET qty = qty - 1, version = version + 1
                            WHERE event_id = :eventId AND shard_no = :shardNo AND version = :version
                            """)
                    .param("eventId", row.eventId())
                    .param("shardNo", row.shardNo())
                    .param("version", current.version())
                    .update();
            if (updated == 1) {
                acquiredAttempts.record(attempt);
                return RowResult.ACQUIRED;
            }
            // Lost the race: someone changed the row after our read. Re-read and try again.
            // No sleep/backoff on purpose: we are inside a transaction holding a DB
            // connection, so sleeping would block a pooled connection while doing nothing.
        }
        exhaustedAttempts.record(maxAttempts);
        return RowResult.CONTENDED;
    }
}
