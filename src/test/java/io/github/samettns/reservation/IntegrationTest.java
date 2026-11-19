package io.github.samettns.reservation;

import io.github.samettns.reservation.support.StockInvariant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Common base for integration tests. Keeping the annotations identical in one place
 * lets Spring reuse a single application context (and a single set of containers)
 * across all test classes instead of starting new ones per class.
 *
 * <p>The scheduled hold-expiry sweeper is disabled: tests that need expiry run sweepers
 * explicitly, so a background sweep can never change state in the middle of an assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "reservation.expiry.enabled=false")
@Import({TestcontainersConfiguration.class, StockInvariant.class})
public abstract class IntegrationTest {
}
