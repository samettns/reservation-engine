package io.github.samettns.reservation.api;

import io.github.samettns.reservation.domain.EventNotFoundException;
import io.github.samettns.reservation.domain.StockSnapshot;
import io.github.samettns.reservation.infra.EventRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventRepository events;

    public EventController(EventRepository events) {
        this.events = events;
    }

    public record CreateEventRequest(@NotBlank String name, @Min(0) @Max(10_000_000) int stock) {
    }

    public record EventCreatedResponse(long id, String name, int stock) {
    }

    /** Setup endpoint used by tests and benchmarks to create an event with stock. */
    @PostMapping
    public ResponseEntity<EventCreatedResponse> create(@Valid @RequestBody CreateEventRequest request) {
        long id = events.create(request.name(), request.stock());
        return ResponseEntity.created(URI.create("/events/" + id + "/stock"))
                .body(new EventCreatedResponse(id, request.name(), request.stock()));
    }

    @GetMapping("/{eventId}/stock")
    public StockSnapshot stock(@PathVariable long eventId) {
        return events.findStock(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }
}
