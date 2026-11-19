package io.github.samettns.reservation.messaging;

import io.github.samettns.reservation.IntegrationTest;
import io.github.samettns.reservation.domain.ConfirmOutcome;
import io.github.samettns.reservation.domain.HoldExpirySweeper;
import io.github.samettns.reservation.domain.Reservation;
import io.github.samettns.reservation.domain.ReservationEvents;
import io.github.samettns.reservation.domain.ReservationOutcome;
import io.github.samettns.reservation.domain.ReservationService;
import io.github.samettns.reservation.domain.ReservationStatus;
import io.github.samettns.reservation.infra.EventRepository;
import io.github.samettns.reservation.infra.OutboxRepository;
import io.github.samettns.reservation.infra.ReservationRepository;
import io.github.samettns.reservation.strategy.ReservationStrategy;
import io.github.samettns.reservation.strategy.StockReturnListener;
import io.github.samettns.reservation.support.ConcurrentRunner;
import io.github.samettns.reservation.support.ConcurrentRunner.Attempt;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Dual-write problem (shown first), then the transactional outbox, parallel relays, Kafka key
 * ordering and consumer-side deduplication. The scheduled relay is disabled in tests; relays are
 * created and run explicitly. The Kafka consumer of the application is running.
 */
class OutboxTest extends IntegrationTest {

    @Autowired
    List<ReservationStrategy> strategies;
    @Autowired
    List<StockReturnListener> stockReturnListeners;
    @Autowired
    ReservationRepository reservations;
    @Autowired
    EventRepository events;
    @Autowired
    ReservationEvents reservationEvents;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    ConsumerFactory<String, String> consumerFactory;
    @Autowired
    MeterRegistry meters;

    /** Other test classes write outbox rows too; start every test with nothing pending. */
    @BeforeEach
    void markEarlierEventsPublished() {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE published_at IS NULL").update();
    }

    // --- 1. the problem: writing to the DB and to Kafka separately ------------

    /** DB commit succeeds, the process dies before the Kafka send: the event is lost forever. */
    @Test
    void dualWriteCommitThenCrashLosesTheEvent() {
        Reservation hold = newHold();

        assertThatThrownBy(() -> {
            tx.executeWithoutResult(s -> reservations.confirmIfHeld(hold.id())); // 1. DB committed
            simulateCrash();                                                     // 2. process dies
            kafka.send(confirmedRecord(hold.id())).get();                         // 3. never runs
        }).hasMessageContaining("crash");

        assertThat(statusOf(hold.id())).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(readTopic(Duration.ofSeconds(3)).stream().filter(r -> r.key().equals(hold.id().toString())))
                .as("payment confirmed in the DB, but nobody downstream will ever hear about it")
                .isEmpty();
    }

    /** Kafka send first, then the DB transaction rolls back: a "phantom" event for a change that never happened. */
    @Test
    void dualWritePublishThenRollbackEmitsAPhantomEvent() {
        Reservation hold = newHold();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            reservations.confirmIfHeld(hold.id());
            send(confirmedRecord(hold.id()));  // already in Kafka, cannot be taken back
            simulateCrash();                   // DB transaction rolls back
        })).hasMessageContaining("crash");

        assertThat(statusOf(hold.id())).as("the confirmation never happened").isEqualTo(ReservationStatus.HELD);
        assertThat(readTopic(Duration.ofSeconds(3)).stream().filter(r -> r.key().equals(hold.id().toString())))
                .as("...but downstream was told it did").hasSize(1);
    }

    // --- 2. the fix: transactional outbox -------------------------------------

    /** The event is a row in the same transaction: a crash after commit cannot lose it, the relay sends it later. */
    @Test
    void outboxSurvivesACrashAfterCommit() {
        Reservation hold = newHold();
        assertThat(service().confirm(hold.id())).isInstanceOf(ConfirmOutcome.Confirmed.class);
        // "crash": no relay has run yet. The event exists as an unpublished row.
        assertThat(unpublishedTypes(hold.id())).containsExactly(ReservationEvents.HELD, ReservationEvents.CONFIRMED);

        relay(100).publishPending(); // the process comes back, the relay catches up

        assertThat(unpublishedTypes(hold.id())).isEmpty();
        assertThat(typesInTopic(hold.id())).containsExactly(ReservationEvents.HELD, ReservationEvents.CONFIRMED);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(notificationTypes(hold.id()))
                        .containsExactly(ReservationEvents.HELD, ReservationEvents.CONFIRMED));
    }

    /** State change rolls back -> its event rolls back with it. No phantom event is possible. */
    @Test
    void outboxRollbackLeavesNoEvent() {
        Reservation hold = newHold();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            reservations.confirmIfHeld(hold.id()).ifPresent(reservationEvents::confirmed);
            simulateCrash();
        })).hasMessageContaining("crash");

        relay(100).publishPending();
        assertThat(statusOf(hold.id())).isEqualTo(ReservationStatus.HELD);
        assertThat(typesInTopic(hold.id())).as("only the real event").containsExactly(ReservationEvents.HELD);
    }

    // --- 3. parallel relays, ordering, consumer exactly-once effect ----------

    /**
     * 300 holds -> 150 confirmed, 50 expired: 500 events for 300 reservations. Three relays (one per
     * app instance) publish in parallel. Every event must be published, events of one reservation
     * must stay in order (same partition, ascending outbox id), and the consumer must apply each
     * event exactly once.
     */
    @Test
    void threeParallelRelaysPublishEverythingInPerReservationOrder() {
        double outOfOrderBefore = counter("reservation.consumer.out_of_order");
        long eventId = events.create("relay-parallel", 300, 4);
        ReservationService service = service();
        List<Reservation> holds = ConcurrentRunner.run(300,
                        i -> service.reserve(eventId, "u" + i, "relay-" + eventId + "-" + i))
                .stream().map(a -> ((ReservationOutcome.Created) a.value()).reservation()).toList();
        ConcurrentRunner.run(150, i -> service.confirm(holds.get(i).id()));
        List<UUID> toExpire = holds.subList(150, 200).stream().map(Reservation::id).toList();
        jdbc.sql("UPDATE reservations SET expires_at = now() - interval '1 second' WHERE id = ANY(:ids)")
                .param("ids", toExpire.toArray(UUID[]::new)).update();
        assertThat(sweeper().sweepOnce()).isEqualTo(50);
        int expectedEvents = 300 + 150 + 50;
        assertThat(outbox.countUnpublished()).isEqualTo(expectedEvents);

        List<OutboxRelay> relays = List.of(relay(20), relay(20), relay(20));
        List<Attempt<Integer>> runs = ConcurrentRunner.run(3, i -> relays.get(i).publishPending());
        // A relay can stop early when the next event of a reservation is still pending in another
        // relay's open transaction; a final pass picks up anything that became publishable.
        int finalPass = relay(100).publishPending();
        List<Integer> perRelay = runs.stream().map(Attempt::value).toList();
        System.out.printf("[3 relays] published per relay: %s, final pass: %d%n", perRelay, finalPass);

        assertThat(runs).noneMatch(Attempt::failed);
        assertThat(perRelay.stream().mapToInt(Integer::intValue).sum() + finalPass)
                .as("each event published once").isEqualTo(expectedEvents);
        assertThat(outbox.countUnpublished()).isZero();

        Set<String> ours = holds.stream().map(r -> r.id().toString()).collect(Collectors.toSet());
        List<ConsumerRecord<String, String>> records = readTopic(Duration.ofSeconds(5)).stream()
                .filter(r -> ours.contains(r.key())).toList();
        assertThat(records).hasSize(expectedEvents);
        Map<String, Set<Integer>> partitionsPerReservation = records.stream().collect(
                Collectors.groupingBy(ConsumerRecord::key, Collectors.mapping(ConsumerRecord::partition, Collectors.toSet())));
        assertThat(partitionsPerReservation.values()).as("one partition per reservation").allMatch(p -> p.size() == 1);
        assertThat(perKeyOutboxIdsAscending(records)).as("sent in outbox order per reservation").isTrue();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(notificationCount(holds)).isEqualTo(expectedEvents));
        assertThat(counter("reservation.consumer.out_of_order") - outOfOrderBefore).isZero();
    }

    /**
     * The exact hazard the per-reservation ordering guard exists for, forced deterministically:
     * relay A has claimed a reservation's "Held" event and has not sent it yet (transaction open);
     * meanwhile relay B runs. B must NOT publish the later "Confirmed" event of the same
     * reservation, otherwise Kafka would receive Confirmed before Held, and the key would then
     * faithfully preserve the wrong order. (The large parallel test above rarely produces this
     * interleaving on its own: removing the guard did not make it fail, so this test was added.)
     */
    @Test
    void laterEventWaitsWhileAnEarlierOneOfTheSameReservationIsInFlight() throws Exception {
        Reservation hold = newHold();
        assertThat(service().confirm(hold.id())).isInstanceOf(ConfirmOutcome.Confirmed.class);
        assertThat(unpublishedTypes(hold.id())).containsExactly(ReservationEvents.HELD, ReservationEvents.CONFIRMED);

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread relayA = Thread.ofVirtual().start(() -> tx.executeWithoutResult(s -> {
            assertThat(outbox.claimBatch(1)).singleElement()
                    .satisfies(m -> assertThat(m.eventType()).isEqualTo(ReservationEvents.HELD));
            claimed.countDown();
            awaitLatch(release); // "sending..." - then crashes: rollback, the row stays unpublished
            s.setRollbackOnly();
        }));
        claimed.await();

        int publishedByB = relay(100).publishPending();
        assertThat(publishedByB).as("relay B must not overtake relay A on the same reservation").isZero();

        release.countDown();
        relayA.join();
        relay(100).publishPending();
        assertThat(typesInTopic(hold.id())).as("Kafka order of this reservation")
                .containsExactly(ReservationEvents.HELD, ReservationEvents.CONFIRMED);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * At-least-once in action: the relay "crashed" after Kafka acknowledged 100 messages but before
     * it committed published_at, so it sends them again. Kafka now holds every one of them twice;
     * the consumer must still apply each exactly once.
     */
    @Test
    void redeliveredMessagesAreAppliedOnlyOnce() {
        long eventId = events.create("redelivery", 100);
        ReservationService service = service();
        List<Reservation> holds = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            holds.add(((ReservationOutcome.Created) service.reserve(eventId, "u" + i, "redeliver-" + eventId + "-" + i))
                    .reservation());
        }
        relay(100).publishPending();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(notificationCount(holds)).isEqualTo(100));
        double duplicatesBefore = counter("reservation.consumer.duplicates");

        UUID[] ids = holds.stream().map(Reservation::id).toArray(UUID[]::new);
        jdbc.sql("UPDATE outbox SET published_at = NULL WHERE aggregate_id = ANY(:ids)").param("ids", ids).update();
        assertThat(relay(100).publishPending()).as("re-sent").isEqualTo(100);

        Set<String> ours = holds.stream().map(r -> r.id().toString()).collect(Collectors.toSet());
        assertThat(readTopic(Duration.ofSeconds(5)).stream().filter(r -> ours.contains(r.key())))
                .as("every message is in Kafka twice").hasSize(200);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(counter("reservation.consumer.duplicates") - duplicatesBefore).isEqualTo(100));
        assertThat(notificationCount(holds)).as("effect applied exactly once per message").isEqualTo(100);
    }

    // --- helpers -------------------------------------------------------------

    private ReservationService service() {
        ReservationStrategy atomic = strategies.stream().filter(s -> s.name().equals("atomic")).findFirst().orElseThrow();
        return new ReservationService(atomic, reservations, events, reservationEvents, tx);
    }

    private HoldExpirySweeper sweeper() {
        return new HoldExpirySweeper(reservations, events, reservationEvents, stockReturnListeners, tx, 200,
                new SimpleMeterRegistry());
    }

    /** A new relay object = what one app instance runs. */
    private OutboxRelay relay(int batchSize) {
        return new OutboxRelay(outbox, kafka, tx, batchSize, Duration.ofSeconds(10), new SimpleMeterRegistry());
    }

    private Reservation newHold() {
        long eventId = events.create("outbox", 1);
        ReservationOutcome outcome = service().reserve(eventId, "alice", "outbox-" + eventId);
        return ((ReservationOutcome.Created) outcome).reservation();
    }

    private static void simulateCrash() {
        throw new IllegalStateException("simulated crash");
    }

    private ProducerRecord<String, String> confirmedRecord(UUID reservationId) {
        ProducerRecord<String, String> record = new ProducerRecord<>(OutboxRelay.TOPIC, reservationId.toString(), "{}");
        record.headers()
                .add(OutboxRelay.HEADER_MESSAGE_ID, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
                .add(OutboxRelay.HEADER_EVENT_TYPE, ReservationEvents.CONFIRMED.getBytes(StandardCharsets.UTF_8))
                .add(OutboxRelay.HEADER_OUTBOX_ID, "0".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void send(ProducerRecord<String, String> record) {
        try {
            kafka.send(record).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ReservationStatus statusOf(UUID id) {
        return reservations.findById(id).orElseThrow().status();
    }

    private List<String> unpublishedTypes(UUID reservationId) {
        return jdbc.sql("SELECT event_type FROM outbox WHERE aggregate_id = :id AND published_at IS NULL ORDER BY id")
                .param("id", reservationId).query(String.class).list();
    }

    private List<String> notificationTypes(UUID reservationId) {
        return jdbc.sql("SELECT event_type FROM notifications WHERE reservation_id = :id ORDER BY id")
                .param("id", reservationId).query(String.class).list();
    }

    private long notificationCount(List<Reservation> holds) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE reservation_id = ANY(:ids)")
                .param("ids", holds.stream().map(Reservation::id).toArray(UUID[]::new))
                .query(Long.class).single();
    }

    private List<String> typesInTopic(UUID reservationId) {
        return readTopic(Duration.ofSeconds(3)).stream()
                .filter(r -> r.key().equals(reservationId.toString()))
                .map(r -> header(r, OutboxRelay.HEADER_EVENT_TYPE))
                .toList();
    }

    /** Reads the whole topic with a fresh consumer group until nothing new arrives for {@code quiet}. */
    private List<ConsumerRecord<String, String>> readTopic(Duration quiet) {
        List<ConsumerRecord<String, String>> all = new ArrayList<>();
        try (Consumer<String, String> consumer = consumerFactory.createConsumer("probe-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(OutboxRelay.TOPIC));
            long lastNew = System.nanoTime();
            while (System.nanoTime() - lastNew < quiet.toNanos()) {
                var batch = consumer.poll(Duration.ofMillis(250));
                if (!batch.isEmpty()) {
                    batch.forEach(all::add);
                    lastNew = System.nanoTime();
                }
            }
        }
        return all;
    }

    private static boolean perKeyOutboxIdsAscending(List<ConsumerRecord<String, String>> records) {
        Map<String, List<ConsumerRecord<String, String>>> byKey = records.stream()
                .collect(Collectors.groupingBy(ConsumerRecord::key));
        for (List<ConsumerRecord<String, String>> list : byKey.values()) {
            List<Long> ids = list.stream()
                    .sorted((a, b) -> Long.compare(a.offset(), b.offset())) // order within the partition
                    .map(r -> Long.parseLong(header(r, OutboxRelay.HEADER_OUTBOX_ID)))
                    .toList();
            for (int i = 1; i < ids.size(); i++) {
                if (ids.get(i) < ids.get(i - 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String header(ConsumerRecord<String, String> r, String name) {
        return new String(r.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }
}
