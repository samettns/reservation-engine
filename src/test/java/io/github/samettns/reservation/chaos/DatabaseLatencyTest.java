package io.github.samettns.reservation.chaos;

import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.StockInvariant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The network to PostgreSQL gets slow: +100 ms per round trip (50 ms each way). A reservation makes
 * about eight round trips, so it now holds a pooled connection for ~1 s.
 *
 * <p>Behaviour: requests that get a connection WAIT (they are just slower); requests that cannot get
 * one within the pool timeout (500 ms) are REFUSED with 503 + Retry-After. Never a 500, never an
 * oversell, and the refused ones can retry with the same Idempotency-Key once the network is fine.
 */
class DatabaseLatencyTest extends ChaosIntegrationTest {

    private static final int STOCK = 30;
    private static final int CLIENTS = 80;

    @Autowired
    TestRestTemplate http;
    @Autowired
    EventRepository events;
    @Autowired
    StockInvariant invariant;

    @Test
    void slowDatabaseMeansSlowerAnswersAnd503sButNeverAnErrorOrAnOversell() throws Exception {
        long eventId = events.create("db-latency", STOCK);
        POSTGRES_PROXY.toxics().latency("slow-down", ToxicDirection.DOWNSTREAM, 50);
        POSTGRES_PROXY.toxics().latency("slow-up", ToxicDirection.UPSTREAM, 50);

        long start = System.nanoTime();
        List<ConcurrentRunner.Attempt<ResponseEntity<String>>> slow = ConcurrentRunner.run(CLIENTS,
                i -> reserve(eventId, "u" + i, "lat-" + eventId + "-" + i));
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        Map<Integer, Long> statuses = slow.stream().map(a -> a.value().getStatusCode().value())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        System.out.printf("[db +100 ms] %d clients in %d ms: %s%n", CLIENTS, tookMs, statuses);

        assertThat(statuses.keySet()).as("only 201, 409 or 503 - never a 500").isSubsetOf(201, 409, 503);
        assertThat(slow.stream().filter(a -> a.value().getStatusCode().value() == 503))
                .allSatisfy(a -> assertThat(a.value().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull());
        long created = statuses.getOrDefault(201, 0L);
        invariant.assertHolds(eventId, created);

        // The network recovers; every refused client retries with its own key.
        healTheNetwork();
        for (int i = 0; i < CLIENTS; i++) {
            if (slow.get(i).value().getStatusCode().value() == 503) {
                reserve(eventId, "u" + i, "lat-" + eventId + "-" + i);
            }
        }
        invariant.assertHolds(eventId, STOCK);                             // sold out exactly, no more
    }

    private ResponseEntity<String> reserve(long eventId, String user, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        return http.postForEntity("/events/{id}/reserve", new HttpEntity<>(Map.of("userId", user), headers),
                String.class, eventId);
    }
}
