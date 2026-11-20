package io.github.samettns.reservation;

import io.github.samettns.reservation.support.StockInvariant;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Like {@link IntegrationTest}, but with the PRODUCTION pool timeout from application.yml (no test
 * override): for tests about overload and outages, where "fails fast" is the behaviour under test.
 * Separate Spring context, so separate containers. (Hikari reads the timeout once when the pool
 * starts; it cannot be switched per test.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"reservation.expiry.enabled=false", "reservation.events.relay.enabled=false",
                "reservation.reconciler.enabled=false"})
@Import({TestcontainersConfiguration.class, StockInvariant.class})
public abstract class FailFastIntegrationTest {
}
