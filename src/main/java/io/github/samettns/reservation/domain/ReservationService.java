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
import java.util.OptionalLong;
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
    /** Test-only pause point between claiming the key and starting the transaction (simulates a GC pause). */
    private final Runnable afterClaim;
    private final DbStepLimiter dbStep;
    private final boolean soldOutShortcuts;
    private final boolean stockLast;

    @Autowired
    public ReservationService(ReservationStrategy strategy,
                              ReservationRepository reservations,
                              EventRepository events,
                              ReservationEvents outbox,
                              IdempotencyRepository idempotency,
                              TransactionTemplate tx,
                              @Value("${reservation.hold-ttl:PT10M}") Duration holdTtl,
                              @Value("${reservation.idempotency.lease:PT30S}") Duration idempotencyLease,
                              MeterRegistry meters,
                              DbStepLimiter dbStep,
                              @Value("${reservation.soldout-shortcuts.enabled:true}") boolean soldOutShortcuts,
                              @Value("${reservation.tx-order:stock-last}") String txOrder) {
        this(strategy, reservations, events, outbox, idempotency, tx, holdTtl, idempotencyLease, meters, () -> { }, dbStep,
                soldOutShortcuts, isStockLast(txOrder));
    }

    /**
     * {@code stock-last} (default since stage 8) or {@code stock-first}. Anything else fails at
     * startup: with stock-last as the default, a typo must not silently select the other order.
     */
    static boolean isStockLast(String txOrder) {
        return switch (txOrder) {
            case "stock-last" -> true;
            case "stock-first" -> false;
            default -> throw new IllegalArgumentException(
                    "reservation.tx-order must be stock-last or stock-first, was: " + txOrder);
        };
    }

    /** Package-private: only tests in this package can inject a pause; production always gets a no-op. */
    ReservationService(ReservationStrategy strategy, ReservationRepository reservations, EventRepository events,
                       ReservationEvents outbox, IdempotencyRepository idempotency, TransactionTemplate tx,
                       Duration holdTtl, Duration idempotencyLease, MeterRegistry meters, Runnable afterClaim) {
        this(strategy, reservations, events, outbox, idempotency, tx, holdTtl, idempotencyLease, meters, afterClaim,
                DbStepLimiter.unlimited());
    }

    ReservationService(ReservationStrategy strategy, ReservationRepository reservations, EventRepository events,
                       ReservationEvents outbox, IdempotencyRepository idempotency, TransactionTemplate tx,
                       Duration holdTtl, Duration idempotencyLease, MeterRegistry meters, Runnable afterClaim,
                       DbStepLimiter dbStep) {
        this(strategy, reservations, events, outbox, idempotency, tx, holdTtl, idempotencyLease, meters, afterClaim,
                dbStep, true, true);
    }

    /**
     * @param soldOutShortcuts false turns OFF both sold-out shortcuts (strategy and marker) and the
     *                         writing of the marker: the stage 8 benchmark measures them on/off in
     *                         the same code.
     * @param stockLast        true = in the reservation transaction, the inserts first and the hot
     *                         stock UPDATE last (the default since stage 8); false = stock first.
     */
    ReservationService(ReservationStrategy strategy, ReservationRepository reservations, EventRepository events,
                       ReservationEvents outbox, IdempotencyRepository idempotency, TransactionTemplate tx,
                       Duration holdTtl, Duration idempotencyLease, MeterRegistry meters, Runnable afterClaim,
                       DbStepLimiter dbStep, boolean soldOutShortcuts, boolean stockLast) {
        this.soldOutShortcuts = soldOutShortcuts;
        this.stockLast = stockLast;
        this.dbStep = dbStep;
        this.afterClaim = afterClaim;
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
                new SimpleMeterRegistry(), () -> { }, DbStepLimiter.unlimited(), true, true);
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
     *
     * <p>With {@code load-shedding.mode=db}, steps 2-4 (the expensive part) are limited by
     * {@link DbStepLimiter}; step 1 never is. A refusal happens before the claim: no side effect.
     */
    public ReservationOutcome reserve(long eventId, String userId, String idempotencyKey) {
        if (soldOutShortcuts && strategy.knownSoldOut(eventId)) {
            redisShortcut.increment();
            return answerWithoutTouchingStock(eventId, userId, idempotencyKey);
        }
        Availability availability = events.findAvailability(eventId)
                .orElseThrow(() -> new EventNotFoundException(eventId));
        if (soldOutShortcuts && availability.soldOut()) {
            markerShortcut.increment();
            return answerWithoutTouchingStock(eventId, userId, idempotencyKey);
        }

        if (!dbStep.tryEnter()) {
            throw new OverloadedException();
        }
        try {
            return reserveAdmitted(eventId, userId, idempotencyKey, availability);
        } finally {
            dbStep.exit();
        }
    }

    /**
     * "Inserts first, hot stock UPDATE last": the default since the stage 8 benchmark (+28..+65%
     * reservations/s in contention for atomic and pessimistic, no loss in the flash sale).
     * The row lock on the hot stock row is taken as late as possible, so it is held for fewer
     * statements: UPDATE stock -> (shard fix-up if sharded) -> INSERT outbox -> COMMIT, instead of
     * UPDATE stock -> INSERT reservation -> INSERT outbox -> UPDATE claim -> COMMIT. (The outbox event
     * carries the shard, so it can only be written once the UPDATE has picked it.) The price: when
     * the event is sold out, the reservation insert and the claim update were done in vain and are
     * rolled back.
     */
    private ReservationOutcome stockLastTransaction(org.springframework.transaction.TransactionStatus status,
                                                    long eventId, String userId, String idempotencyKey,
                                                    long fenceToken) {
        Reservation hold = reservations.insertHeld(eventId, 0, userId, idempotencyKey, holdTtl);
        if (!idempotency.complete(userId, idempotencyKey, hold.id(), fenceToken)) {
            throw new StaleClaimException(idempotencyKey, fenceToken);
        }
        OptionalInt shard = strategy.tryAcquireOne(eventId);              // hot row lock: LAST
        if (shard.isEmpty()) {
            status.setRollbackOnly();                                     // undo the inserts above
            return new ReservationOutcome.SoldOut(eventId);
        }
        if (shard.getAsInt() != 0) {
            hold = reservations.moveToShard(hold.id(), shard.getAsInt());
        }
        outbox.held(hold);
        return new ReservationOutcome.Created(hold);
    }

    /** The expensive part (claim + stock transaction); limited by {@link DbStepLimiter} in mode=db. */
    private ReservationOutcome reserveAdmitted(long eventId, String userId, String idempotencyKey,
                                               Availability availability) {
        OptionalLong claim = idempotency.claim(userId, idempotencyKey, eventId, idempotencyLease);
        if (claim.isEmpty()) {
            return existingOutcome(eventId, userId, idempotencyKey)
                    // the owner released the key a moment ago (no side effect); retrying is safe
                    .orElse(new ReservationOutcome.InProgress(eventId));
        }
        long fenceToken = claim.getAsLong();
        afterClaim.run();

        boolean created = false;
        try {
            ReservationOutcome outcome = tx.execute(status -> {
                // Fencing, step 1: still the owner? Also locks the claim row for this transaction, so
                // the key cannot be taken over while we work. A slow owner whose lease was taken over
                // stops HERE, before touching any stock.
                if (!idempotency.lockClaim(userId, idempotencyKey, fenceToken)) {
                    throw new StaleClaimException(idempotencyKey, fenceToken);
                }
                if (stockLast) {
                    return stockLastTransaction(status, eventId, userId, idempotencyKey, fenceToken);
                }
                OptionalInt shard = strategy.tryAcquireOne(eventId);
                if (shard.isEmpty()) {
                    // Every shard was empty: remember it, as of the stock version read above.
                    if (soldOutShortcuts) {
                        events.markSoldOut(eventId, availability.stockVersion());
                    }
                    return new ReservationOutcome.SoldOut(eventId);
                }
                Reservation hold = reservations.insertHeld(eventId, shard.getAsInt(), userId, idempotencyKey, holdTtl);
                outbox.held(hold);
                // Fencing, step 2 (defence in depth): COMPLETED only with the current token.
                if (!idempotency.complete(userId, idempotencyKey, hold.id(), fenceToken)) {
                    throw new StaleClaimException(idempotencyKey, fenceToken); // rolls back hold + stock
                }
                return new ReservationOutcome.Created(hold);
            });
            created = outcome instanceof ReservationOutcome.Created;
            if (stockLast && soldOutShortcuts && outcome instanceof ReservationOutcome.SoldOut) {
                // stock-last rolled its transaction back (the inserts were in vain); the marker is
                // written on its own, as of the stock version read before the claim.
                events.markSoldOut(eventId, availability.stockVersion());
            }
            return outcome;
        } catch (StaleClaimException stale) {
            // Our lease was taken over while we were slow; everything we did was rolled back.
            // Answer with the new owner's state.
            return existingOutcome(eventId, userId, idempotencyKey).orElse(new ReservationOutcome.InProgress(eventId));
        } catch (DuplicateKeyException concurrentDuplicate) {
            // Last safety net: the unique (user, key) constraint on reservations. With fencing this
            // should not be reachable, but if it is, only one insert wins and ours rolled back.
            return reservations.findByIdempotencyKey(userId, idempotencyKey)
                    .<ReservationOutcome>map(ReservationOutcome.Replayed::new)
                    .orElseThrow(() -> concurrentDuplicate);
        } finally {
            if (!created) {
                // Fencing, step 3: only the CURRENT owner can release; a stale token deletes nothing.
                idempotency.release(userId, idempotencyKey, fenceToken);
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
