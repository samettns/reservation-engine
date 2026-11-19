package io.github.samettns.reservation.api;

import io.github.samettns.reservation.TestcontainersConfiguration;
import io.github.samettns.reservation.api.EventController.EventCreatedResponse;
import io.github.samettns.reservation.api.ReservationController.ReservationResponse;
import io.github.samettns.reservation.domain.StockSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReservationApiTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void reservesUntilSoldOut() {
        long eventId = createEvent(2);

        assertThat(reserve(eventId, "alice", newKey()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(eventId, "bob", newKey()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> third = reserve(eventId, "carol", newKey());
        assertThat(third.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(third.getBody()).contains("sold out");

        StockSnapshot stock = stock(eventId);
        assertThat(stock.remaining()).isZero();
        assertThat(stock.reserved()).isEqualTo(2);
        assertThat(stock.isConsistent()).isTrue();
    }

    @Test
    void sameIdempotencyKeyReturnsOriginalReservationWithoutTakingStockAgain() {
        long eventId = createEvent(5);
        String key = newKey();

        ResponseEntity<ReservationResponse> first = reserveTyped(eventId, "alice", key);
        ResponseEntity<ReservationResponse> retry = reserveTyped(eventId, "alice", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst(ReservationController.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(retry.getBody().reservationId()).isEqualTo(first.getBody().reservationId());

        StockSnapshot stock = stock(eventId);
        assertThat(stock.remaining()).isEqualTo(4);
        assertThat(stock.reserved()).isEqualTo(1);
    }

    @Test
    void sameKeyFromDifferentUsersAreIndependentRequests() {
        long eventId = createEvent(5);
        String key = newKey();

        assertThat(reserve(eventId, "alice", key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reserve(eventId, "bob", key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stock(eventId).remaining()).isEqualTo(3);
    }

    @Test
    void reusingKeyForDifferentEventIsRejected() {
        long firstEvent = createEvent(5);
        long secondEvent = createEvent(5);
        String key = newKey();

        reserve(firstEvent, "alice", key);
        ResponseEntity<ProblemDetail> response = http.postForEntity(
                "/events/{id}/reserve", request("alice", key), ProblemDetail.class, secondEvent);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(stock(secondEvent).remaining()).isEqualTo(5);
    }

    @Test
    void unknownEventReturns404() {
        ResponseEntity<ProblemDetail> response = http.postForEntity(
                "/events/{id}/reserve", request("alice", newKey()), ProblemDetail.class, 999_999);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/events/999999/stock", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        long eventId = createEvent(1);
        HttpEntity<Map<String, String>> noKey = new HttpEntity<>(Map.of("userId", "alice"));

        ResponseEntity<ProblemDetail> response = http.postForEntity(
                "/events/{id}/reserve", noKey, ProblemDetail.class, eventId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stock(eventId).remaining()).isEqualTo(1);
    }

    @Test
    void blankUserIdReturns400() {
        long eventId = createEvent(1);

        ResponseEntity<ProblemDetail> response = http.postForEntity(
                "/events/{id}/reserve", request(" ", newKey()), ProblemDetail.class, eventId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers -------------------------------------------------------------

    private long createEvent(int stock) {
        ResponseEntity<EventCreatedResponse> response = http.postForEntity(
                "/events", Map.of("name", "test-event", "stock", stock), EventCreatedResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().id();
    }

    private ResponseEntity<String> reserve(long eventId, String userId, String key) {
        return http.postForEntity("/events/{id}/reserve", request(userId, key), String.class, eventId);
    }

    private ResponseEntity<ReservationResponse> reserveTyped(long eventId, String userId, String key) {
        return http.postForEntity("/events/{id}/reserve", request(userId, key), ReservationResponse.class, eventId);
    }

    private StockSnapshot stock(long eventId) {
        return http.getForObject("/events/{id}/stock", StockSnapshot.class, eventId);
    }

    private static HttpEntity<Map<String, String>> request(String userId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ReservationController.IDEMPOTENCY_KEY_HEADER, key);
        return new HttpEntity<>(Map.of("userId", userId), headers);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
