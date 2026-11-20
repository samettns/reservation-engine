package io.github.samettns.reservation.domain;

/** The DB step is at its in-flight limit: refused before anything was claimed or written (503). */
public class OverloadedException extends RuntimeException {

    public OverloadedException() {
        super("Too many reservations in progress on this instance; retry later");
    }
}
