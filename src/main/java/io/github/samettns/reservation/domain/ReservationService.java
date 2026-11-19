package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class ReservationService {

    private final ReservationStrategy strategy;
    private final ReservationRepository reservations;
    private final EventRepository events;
    private final TransactionTemplate tx;

    public ReservationService(ReservationStrategy strategy,
                              ReservationRepository reservations,
                              EventRepository events,
                              TransactionTemplate tx) {
        this.strategy = strategy;
        this.reservations = reservations;
        this.events = events;
        this.tx = tx;
    }

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
                if (!strategy.tryAcquireOne(eventId)) {
                    return new ReservationOutcome.SoldOut(eventId);
                }
                Reservation created = reservations.insert(
                        eventId, userId, idempotencyKey, ReservationStatus.CONFIRMED);
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
