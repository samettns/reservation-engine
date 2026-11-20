package io.github.samettns.reservation.api;

import com.zaxxer.hikari.HikariDataSource;
import io.github.samettns.reservation.FailFastIntegrationTest;
import io.github.samettns.reservation.infra.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Overload answers 503 + Retry-After (a retry can work), never 500, and refuses FAST:
 * <ul>
 *   <li>load shedding: the instance's in-flight limit is reached, refused before any work;</li>
 *   <li>pool exhaustion: no DB connection within the (short) pool timeout.</li>
 * </ul>
 * Neither leaves a side effect: a retry with the same Idempotency-Key succeeds normally.
 */
class OverloadTest extends FailFastIntegrationTest {

    @Autowired
    TestRestTemplate http;
    @Autowired
    LoadSheddingFilter shedding;
    @Autowired
    HikariDataSource dataSource;
    @Autowired
    EventRepository events;

    @Test
    void fullInstanceShedsReservationsImmediatelyWith503() {
        long eventId = events.create("shed", 5);
        String key = UUID.randomUUID().toString();
        int occupied = occupyAllSlots();
        try {
            long start = System.nanoTime();
            ResponseEntity<String> refused = reserve(eventId, "alice", key);
            long tookMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(refused.getHeaders().getContentType()).hasToString("application/problem+json");
            assertThat(refused.getBody()).contains(LoadSheddingFilter.OVERLOADED_TYPE.toString());
            assertThat(tookMs).as("refused without queueing").isLessThan(1_000);

            ResponseEntity<String> confirm = http.postForEntity("/reservations/{id}/confirm", null, String.class,
                    UUID.randomUUID());
            assertThat(confirm.getStatusCode()).as("payment confirmations are never shed")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            freeSlots(occupied);
        }
        assertThat(reserve(eventId, "alice", key).getStatusCode()).as("same key, no side effect")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void exhaustedConnectionPoolAnswers503FastInsteadOf500() throws Exception {
        long eventId = events.create("pool", 5);
        String key = UUID.randomUUID().toString();
        List<Connection> held = holdAllConnections();
        try {
            long start = System.nanoTime();
            ResponseEntity<String> refused = reserve(eventId, "alice", key);
            long tookMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(refused.getBody()).contains(ApiExceptionHandler.DEPENDENCY_UNAVAILABLE_TYPE.toString());
            // Fixed bound on purpose (not derived from the configured timeout): the point is "fast".
            assertThat(tookMs).as("fails fast (pool timeout %d ms), not after the old 5 s", dataSource.getConnectionTimeout())
                    .isLessThan(2_000);
        } finally {
            for (Connection c : held) {
                c.close();
            }
        }
        assertThat(reserve(eventId, "alice", key).getStatusCode()).as("same key, no side effect")
                .isEqualTo(HttpStatus.CREATED);
    }

    // --- helpers -------------------------------------------------------------

    private int occupyAllSlots() {
        int n = 0;
        while (shedding.occupy()) {
            n++;
        }
        return n;
    }

    private void freeSlots(int n) {
        for (int i = 0; i < n; i++) {
            shedding.free();
        }
    }

    /** Takes every connection of the pool (waiting for ones other components are briefly using). */
    private List<Connection> holdAllConnections() throws SQLException {
        List<Connection> held = new ArrayList<>();
        try {
            while (held.size() < dataSource.getMaximumPoolSize()) {
                held.add(dataSource.getConnection());
            }
        } catch (SQLException e) {
            for (Connection c : held) {
                c.close();
            }
            throw e;
        }
        return held;
    }

    private ResponseEntity<String> reserve(long eventId, String userId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/events/{id}/reserve", new HttpEntity<>(Map.of("userId", userId), headers),
                String.class, eventId);
    }
}
