package io.github.samettns.reservation.domain;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID reservationId) {
        super("Reservation " + reservationId + " does not exist");
    }
}
