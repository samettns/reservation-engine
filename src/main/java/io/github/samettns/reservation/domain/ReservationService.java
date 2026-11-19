package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.EventRepository.Availability;
import io.github.samettns.reservation.infra.IdempotencyRepository;
import io.github.samettns.reservation.infra.IdempotencyRepository.IdempotencyRecord;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    public static final Duration DEFAULT_IDEMPOTENCY_LEASE = Duration.ofSeconds(30);
    static final String SHORTCUT_METRIC = "reservation.soldout.shortcut";

    private final ReservationStrategy strategy;
    private final ReservationRepository reservations;
    private final EventRepository events;
    private final ReservationEvents outbox;
    private final IdempotencyRepository idempotency;
    private final TransactionTemplate tx;
    private final Duration holdTtl;
    private final Duration idempotencyLease;
    private final Counter redisShortcut;
    private final Counter markerShortcut;

    @Autowired
    public ReservationService(ReservationStrategy strategy,
                              ReservationRepository reservations,
                              EventRepository events,
                              ReservationEvents outbox,
                              IdempotencyRepository idempotency,
                              TransactionTemplate tx,
                              @Value("${reservation.hold-ttl:PT10M}") Duration holdTtl,
                              @Value("${reservation.idempotency.lease:PT30S}") Duration idempotencyLease,
                              MeterRegistry meters) {
        this.strategy = strategy;
        this.reservations = reservations;
        this.events = events;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.tx = tx;
        this.holdTtl = holdTtl;
        this.idempotencyLease = idempotencyLease;
        this.redisShortcut = Counter.builder(SHORTCUT_METRIC).tag("source", "strategy").register(meters);
        this.markerShortcut = Counter.builder(SHORTCUT_METRIC).tag("source", "marker").register(meters);
    }

    /** Convenience for tests that wire a service with a specific strategy. */
    public ReservationService(ReservationStrategy strategy, ReservationRepository reservations, EventRepository events,
                              ReservationEvents outbox, IdempotencyRepository idempotency, TransactionTemplate tx) {
        this(strategy, reservations, events, outbox, idempotency, tx, DEFAULT_HOLD_TTL, DEFAULT_IDEMPOTENCY_LEASE,
                new SimpleMeterRegistry());
    }

    /**
     * Takes one unit and HOLDS it until {@code now + hold-ttl}; payment must confirm it before then.
     *
     * <ol>
     *   <li><b>Sold-out shortcuts</b> (no stock row, no transaction): the strategy's own check
     *       (Redis counter at 0), then the versioned sold-out marker on the event row.</li>
     *   <li><b>Claim the idempotency key</b> (committed on its own, BEFORE stock is touched).
     *       A concurrent twin of this request now sees IN_PROGRESS instead of racing for stock.</li>
     *   <li><b>One transaction</b>: take a unit, insert the hold, write its event, mark the key
     *       COMPLETED. All or nothing.</li>
     *   <li>No side effect (sold out, 503, error): release the key so the client can retry.</li>
     * </ol>
     */
    public ReservationOutcome reserve(long eventId, String userId, String idempotencyKey) {
        if (strategy.knownSoldOut(eventId)) {
            redisShortcut.increment();
            return answerWithoutTouchingStock(eventId, userId, idempotencyKey);
        }
        Availability availability = events.findAvailability(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (availability.soldOut()) {
            markerShortcut.increment();
            return answerWithoutTouchingStock(eventId, userId, idempotencyKey);
        }

        if (!idempotency.claim(userId, idempotencyKey, eventId, idempotencyLease)) {
            return existingOutcome(eventId, userId, idempotencyKey)
                    // the owner released the key a moment ago (no side effect); retrying is safe
                    .orElse(new ReservationOutcome.InProgress(eventId));
        }

        boolean created = false;
        try {
            ReservationOutcome outcome = tx.execute(status -> {
                OptionalInt shard = strategy.tryAcquireOne(eventId);
                if (shard.isEmpty()) {
                    // Every shard was empty: remember it, as of the stock version read above.
                    events.markSoldOut(eventId, availability.stockVersion());
                    return new ReservationOutcome.SoldOut(eventId);
                }
                Reservation hold = reservations.insertHeld(eventId, shard.getAsInt(), userId, idempotencyKey, holdTtl);
                outbox.held(hold);
                idempotency.complete(userId, idempotencyKey, hold.id());
                return new ReservationOutcome.Created(hold);
            });
            created = outcome instanceof ReservationOutcome.Created;
            return outcome;
        } catch (DuplicateKeyException concurrentDuplicate) {
            // Safety net below the claim: if a lease expired while its owner was still working, two
            // requests can hold the claim. The unique (user, key) constraint on reservations still
            // lets only one insert win; ours rolled back (stock included). Return the winner's result.
            return reservations.findByIdempotencyKey(userId, idempotencyKey)
                    .<ReservationOutcome>map(ReservationOutcome.Replayed::new)
                    .orElseThrow(() -> concurrentDuplicate);
        } finally {
            if (!created) {
                idempotency.release(userId, idempotencyKey); // no-op if a winner COMPLETED it
            }
        }
    }

    /**
     * The event is sold out. A request with this key may still have succeeded earlier (replay) or be
     * in flight right now (its claim was committed BEFORE it took the unit that made the count reach
     * zero, so it is guaranteed to be visible here). Only otherwise is the answer "sold out".
     */
    private ReservationOutcome answerWithoutTouchingStock(long eventId, String userId, String idempotencyKey) {
        return existingOutcome(eventId, userId, idempotencyKey).orElse(new ReservationOutcome.SoldOut(eventId));
    }

    /** Replayed / InProgress for a known key, 422 for a key used with another event, empty if unknown. */
    private Optional<ReservationOutcome> existingOutcome(long eventId, String userId, String idempotencyKey) {
        Optional<IdempotencyRecord> record = idempotency.find(userId, idempotencyKey);
        if (record.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyRecord r = record.get();
        if (r.eventId() != eventId) {
            throw new IdempotencyKeyReusedException(idempotencyKey, r.eventId());
        }
        return switch (r.status()) {
            case COMPLETED -> Optional.of(new ReservationOutcome.Replayed(
                    reservations.findById(r.reservationId()).orElseThrow()));
            case IN_PROGRESS -> r.live()
                    ? Optional.of(new ReservationOutcome.InProgress(eventId))
                    : Optional.empty(); // abandoned claim of a crashed request: it had no effect
        };
    }

    /**
     * Payment callback: HELD -> CONFIRMED if still before the deadline. Safe to call repeatedly
     * (payment providers retry callbacks): confirming an already confirmed hold returns it again.
     */
    public ConfirmOutcome confirm(UUID reservationId) {
        Optional<Reservation> confirmed = tx.execute(status -> {
            Optional<Reservation> r = reservations.confirmIfHeld(reservationId);
            r.ifPresent(outbox::confirmed); // event only if THIS call made the transition
            return r;
        });
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
}
