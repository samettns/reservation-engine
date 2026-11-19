package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

@Service
public class ReservationService {

    public static final Duration DEFAULT_HOLD_TTL = Duration.ofMinutes(10);

    private final ReservationStrategy strategy;
    private final ReservationRepository reservations;
    private final EventRepository events;
    private final TransactionTemplate tx;
    private final Duration holdTtl;

    @Autowired
    public ReservationService(ReservationStrategy strategy,
                              ReservationRepository reservations,
                              EventRepository events,
                              TransactionTemplate tx,
                              @Value("${reservation.hold-ttl:PT10M}") Duration holdTtl) {
        this.strategy = strategy;
        this.reservations = reservations;
        this.events = events;
        this.tx = tx;
        this.holdTtl = holdTtl;
    }

    /** Convenience for tests that wire a service with a specific strategy. */
    public ReservationService(ReservationStrategy strategy, ReservationRepository reservations,
                              EventRepository events, TransactionTemplate tx) {
        this(strategy, reservations, events, tx, DEFAULT_HOLD_TTL);
    }

    /** Takes one unit and HOLDS it until {@code now + hold-ttl}; payment must confirm it before then. */
    public ReservationOutcome reserve(long eventId, String userId, String idempotencyKey) {
        // Fast path: a retry of a request we already completed. Cheap read, no stock touched.
        Optional<ReservationOutcome> replay = findReplay(eventId, userId, idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!events.exists(eventId)) {
            throw new EventNotFoundException(eventId);
        }

        try {
            // Stock decrement and reservation insert commit or roll back TOGETHER.
            // TransactionTemplate (instead of @Transactional) keeps the transaction
            // boundary visible right here, and lets us catch the exception below
            // after the rollback has already happened.
            ReservationOutcome outcome = tx.execute(status -> {
                OptionalInt shard = strategy.tryAcquireOne(eventId);
                if (shard.isEmpty()) {
                    return new ReservationOutcome.SoldOut(eventId);
                }
                // The shard is stored so that an expired hold returns its unit to the same row.
                Reservation created = reservations.insertHeld(
                        eventId, shard.getAsInt(), userId, idempotencyKey, holdTtl);
                return new ReservationOutcome.Created(created);
            });
            if (outcome instanceof ReservationOutcome.SoldOut) {
                // A concurrent twin of THIS request (same key) may have taken the last
                // unit. Telling the client "sold out" would be wrong: their request
                // succeeded. Re-check before answering. With DB strategies the twin waited
                // on the row lock until the winner committed, so this always finds it.
                // With the Redis gate the winner may not have committed yet, so a small
                // window remains (full fix: in-progress idempotency records, stage 5).
                return findReplay(eventId, userId, idempotencyKey).orElse(outcome);
            }
            return outcome;
        } catch (DuplicateKeyException concurrentDuplicate) {
            // Two requests with the same key passed the fast path at the same time.
            // The unique constraint let only one insert win; ours was rolled back,
            // which also gave back the unit of stock we took. Return the winner's result.
            return findReplay(eventId, userId, idempotencyKey)
                    .orElseThrow(() -> concurrentDuplicate);
        }
    }

    /**
     * Payment callback: HELD -> CONFIRMED if still before the deadline. Safe to call repeatedly
     * (payment providers retry callbacks): confirming an already confirmed hold returns it again.
     */
    public ConfirmOutcome confirm(UUID reservationId) {
        Optional<Reservation> confirmed = reservations.confirmIfHeld(reservationId);
        if (confirmed.isPresent()) {
            return new ConfirmOutcome.Confirmed(confirmed.get());
        }
        Reservation current = reservations.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        return switch (current.status()) {
            case CONFIRMED -> new ConfirmOutcome.AlreadyConfirmed(current);
            // EXPIRED, or still HELD but past the deadline (the sweeper has not run yet):
            // either way the payment came too late and the unit is (or will be) back in stock.
            case EXPIRED, HELD -> new ConfirmOutcome.Expired(current);
        };
    }

    private Optional<ReservationOutcome> findReplay(long eventId, String userId, String idempotencyKey) {
        return reservations.findByIdempotencyKey(userId, idempotencyKey)
                .map(existing -> {
                    if (existing.eventId() != eventId) {
                        throw new IdempotencyKeyReusedException(idempotencyKey, existing.eventId());
                    }
                    return new ReservationOutcome.Replayed(existing);
                });
    }
}
