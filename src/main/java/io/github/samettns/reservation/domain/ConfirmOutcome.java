package io.github.samettns.reservation.domain;

/** Result of a payment confirmation for a hold. */
public sealed interface ConfirmOutcome {

    /** This call moved the hold from HELD to CONFIRMED. */
    record Confirmed(Reservation reservation) implements ConfirmOutcome {
    }

    /** Already CONFIRMED by an earlier call (e.g. a retried payment callback). */
    record AlreadyConfirmed(Reservation reservation) implements ConfirmOutcome {
    }

    /** The deadline passed before payment; the unit is (or will be) back in stock. */
    record Expired(Reservation reservation) implements ConfirmOutcome {
    }
}
