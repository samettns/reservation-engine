package io.github.samettns.reservation.domain;

import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.infra.ReservationRepository.ExpiredHold;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns overdue HELD reservations into EXPIRED and gives their units back to the shard they came
 * from. Runs on every app instance at the same time (see {@link HoldExpiryScheduler}); correctness
 * does not depend on there being only one sweeper:
 * <ul>
 *   <li>{@code FOR UPDATE SKIP LOCKED} + {@code status = 'HELD'} (see
 *       {@link ReservationRepository#expireDueHolds}): each hold is expired by exactly one sweeper.</li>
 *   <li>Marking EXPIRED and returning the stock happen in ONE transaction: a crash in between
 *       rolls both back, so a unit is never returned without the hold being expired, or vice versa.</li>
 *   <li>Stock rows are updated in a fixed order (event, shard): two sweepers returning units to the
 *       same rows lock them in the same order and cannot deadlock each other.</li>
 * </ul>
 */
@Component
public class HoldExpirySweeper {

    private record ShardKey(long eventId, int shardNo) {
    }

    private static final Comparator<ShardKey> LOCK_ORDER =
            Comparator.comparingLong(ShardKey::eventId).thenComparingInt(ShardKey::shardNo);

    private final ReservationRepository reservations;
    private final EventRepository events;
    private final List<StockReturnListener> listeners;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final Counter expiredCounter;

    @Autowired
    public HoldExpirySweeper(ReservationRepository reservations,
                             EventRepository events,
                             List<StockReturnListener> listeners,
                             TransactionTemplate tx,
                             @Value("${reservation.expiry.batch-size:200}") int batchSize,
                             MeterRegistry meters) {
        this.reservations = reservations;
        this.events = events;
        this.listeners = listeners;
        this.tx = tx;
        this.batchSize = batchSize;
        this.expiredCounter = Counter.builder("reservation.holds.expired").register(meters);
    }

    /** Expires everything that is due, batch by batch (one transaction per batch). */
    public int sweepOnce() {
        int total = 0;
        while (true) {
            Integer expired = tx.execute(status -> expireBatch());
            total += expired;
            if (expired < batchSize) {
                return total;
            }
        }
    }

    /**
     * One batch. Must run inside a transaction: the caller's commit makes the EXPIRED status and
     * the returned stock visible together.
     */
    public int expireBatch() {
        List<ExpiredHold> expired = reservations.expireDueHolds(batchSize);
        if (expired.isEmpty()) {
            return 0;
        }
        Map<ShardKey, Integer> unitsPerShard = new TreeMap<>(LOCK_ORDER);
        for (ExpiredHold hold : expired) {
            unitsPerShard.merge(new ShardKey(hold.eventId(), hold.shardNo()), 1, Integer::sum);
        }
        Map<Long, Integer> unitsPerEvent = new TreeMap<>();
        unitsPerShard.forEach((shard, units) -> {
            events.returnStock(shard.eventId(), shard.shardNo(), units);
            unitsPerEvent.merge(shard.eventId(), units, Integer::sum);
        });
        unitsPerEvent.forEach((eventId, units) -> listeners.forEach(l -> l.onStockReturned(eventId, units)));
        expiredCounter.increment(expired.size());
        return expired.size();
    }
}
