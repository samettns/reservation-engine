package io.github.samettns.reservation;

import io.github.samettns.reservation.support.StockInvariant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Common base for integration tests. Keeping the annotations identical in one place
 * lets Spring reuse a single application context (and a single set of containers)
 * across all test classes instead of starting new ones per class.
 *
 * <p>Background jobs are disabled: the hold-expiry sweeper and the outbox relay are created and
 * driven explicitly by the tests that need them, so a background run can never change state in
 * the middle of an assertion. The Kafka consumer stays on.
 *
 * <p>The pool timeout is long in tests: concurrency tests start hundreds of threads against a
 * 20-connection pool ON PURPOSE and check correctness under contention, not overload. Tests about
 * overload use the production timeout ({@link FailFastIntegrationTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"reservation.expiry.enabled=false", "reservation.events.relay.enabled=false",
                "reservation.reconciler.enabled=false", "spring.datasource.hikari.connection-timeout=10000"})
@Import({TestcontainersConfiguration.class, StockInvariant.class})
public abstract class IntegrationTest {
}
