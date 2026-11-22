package io.github.samettns.reservation.chaos;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.Toxic;
import io.github.samettns.reservation.strategy.CircuitBreaker;
import io.github.samettns.reservation.strategy.RedisGateStrategy;
import io.github.samettns.reservation.support.StockInvariant;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Base for the fault-injection tests (stage 7b). The application reaches PostgreSQL, Redis and
 * Kafka ONLY through Toxiproxy, so a test can cut a connection, add latency or reset it, while the
 * real services keep running.
 *
 * <pre>
 *   app (test JVM) --> toxiproxy:8666 --> postgres:5432
 *                  --> toxiproxy:8667 --> redis:6379
 *                  --> toxiproxy:8668 --> kafka:19093
 * </pre>
 *
 * Kafka needs one more step: after the first request a Kafka client connects to the address the
 * broker ADVERTISES. That address is the proxy's, so every later connection goes through it too.
 *
 * <p>Own Spring context (its own containers, production timeouts). Every test starts with all
 * proxies enabled and no toxics.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"reservation.expiry.enabled=false", "reservation.events.relay.enabled=false",
                "reservation.reconciler.enabled=false"})
@Import(StockInvariant.class)
public abstract class ChaosIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.12.0")
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy");
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(NETWORK).withNetworkAliases("postgres");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);
    static final KafkaContainer KAFKA;
    static final Proxy POSTGRES_PROXY;
    static final Proxy REDIS_PROXY;
    static final Proxy KAFKA_PROXY;

    static {
        TOXIPROXY.start();
        POSTGRES.start();
        REDIS.start();
        ToxiproxyClient toxiproxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        try {
            POSTGRES_PROXY = toxiproxy.createProxy("postgres", "0.0.0.0:8666", "postgres:5432");
            REDIS_PROXY = toxiproxy.createProxy("redis", "0.0.0.0:8667", "redis:6379");
            KAFKA_PROXY = toxiproxy.createProxy("kafka", "0.0.0.0:8668", "kafka:19093");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        KAFKA = new KafkaContainer("apache/kafka:3.9.1")
                .withNetwork(NETWORK).withNetworkAliases("kafka")
                .withListener("kafka:19093", () -> TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8668));
        KAFKA.start();
    }

    @DynamicPropertySource
    static void throughToxiproxy(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + TOXIPROXY.getHost() + ":"
                + TOXIPROXY.getMappedPort(8666) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(8667));
        registry.add("spring.kafka.bootstrap-servers", () -> TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8668));
    }

    @Autowired
    RedisGateStrategy redisGate;
    @Autowired
    StringRedisTemplate redisTemplate;

    /**
     * Every test leaves the network healthy for the next one, and that includes the APPLICATION's
     * view of it: re-enabling a proxy is not enough. Right after a Redis outage the app's Lettuce
     * connection is still reconnecting and the gate's circuit breaker may be open; a test starting
     * then (the CI runner's class order put RedisRecoveryTest right after RedisOutageTest) had its
     * first request skip Redis (fail open) and waited 20 s for a counter that was never created.
     * So wait until the app's own connection answers PING and the breaker is closed again.
     */
    @AfterEach
    void healTheNetwork() throws IOException {
        for (Proxy proxy : new Proxy[]{POSTGRES_PROXY, REDIS_PROXY, KAFKA_PROXY}) {
            for (Toxic toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
            proxy.enable();
        }
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).ignoreExceptions().until(() -> {
            redisGate.knownSoldOut(Long.MIN_VALUE);   // a real gate call: lets the breaker's probe through
            return "PONG".equals(redisTemplate.execute((RedisCallback<String>) RedisConnection::ping))
                    && redisGate.breakerState() == CircuitBreaker.State.CLOSED;
        });
    }
}
