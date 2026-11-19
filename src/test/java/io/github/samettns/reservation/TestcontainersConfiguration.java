package io.github.samettns.reservation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL in Docker for tests. No H2: locking, isolation and constraint
 * behaviour are exactly what this project is about, and H2 does them differently.
 *
 * <p>{@code @ServiceConnection} wires the container's URL/credentials into the
 * DataSource. Spring caches the test context, so all test classes sharing this
 * configuration reuse one container.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Same image as docker-compose.yml so tests and local runs behave identically. */
    static final String POSTGRES_IMAGE = "postgres:17-alpine";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
