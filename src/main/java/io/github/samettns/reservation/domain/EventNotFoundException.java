package io.github.samettns.reservation.domain;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(long eventId) {
        super("Event " + eventId + " does not exist");
    }
}
