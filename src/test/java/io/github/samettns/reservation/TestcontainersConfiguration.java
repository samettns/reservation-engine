package io.github.samettns.reservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real PostgreSQL, Redis and Kafka in Docker for tests. No H2 / embedded fakes: locking,
 * isolation, constraint and delivery behaviour are exactly what this project is about.
 *
 * <p>{@code @ServiceConnection} wires each container's address into Spring's
 * auto-configuration. Spring caches the test context, so all test classes extending
 * {@link IntegrationTest} reuse the same containers.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same images as docker-compose.yml so tests and local runs behave identically. */
    static final String POSTGRES_IMAGE = "postgres:17-alpine";
    static final String REDIS_IMAGE = "redis:7.4-alpine";
    static final String KAFKA_IMAGE = "apache/kafka:3.9.1";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer(KAFKA_IMAGE);
    }
}
