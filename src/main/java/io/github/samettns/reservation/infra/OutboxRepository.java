package io.github.samettns.reservation.infra;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    /** A row waiting to be published. {@code id} is the global outbox order. */
    public record OutboxMessage(long id, UUID messageId, UUID aggregateId, String eventType, String payload) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the transaction that makes the state change the event describes. */
    public void add(UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_id, event_type, payload)
                        VALUES (:aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Claims up to {@code limit} unpublished rows for this relay, oldest first. Must run inside a
     * transaction that stays open until the rows are sent and marked published.
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED}: parallel relays (one per app instance) split the rows
     *       instead of publishing the same row at the same time.</li>
     *   <li>{@code NOT EXISTS (older unpublished row of the same reservation)}: only the OLDEST
     *       pending event of a reservation can be claimed. Without it, relay A could hold "Held"
     *       while relay B sends the later "Confirmed" first, and the Kafka key would then faithfully
     *       preserve the WRONG order. The next event becomes claimable once the previous one is
     *       committed as published.</li>
     * </ul>
     */
    public List<OutboxMessage> claimBatch(int limit) {
        return jdbc.sql("""
                        SELECT o.id, o.message_id, o.aggregate_id, o.event_type, o.payload::text AS payload
                        FROM outbox o
                        WHERE o.published_at IS NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM outbox earlier
                              WHERE earlier.aggregate_id = o.aggregate_id
                                AND earlier.published_at IS NULL
                                AND earlier.id < o.id)
                        ORDER BY o.id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(List<Long> ids) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .update();
    }

    public long countUnpublished() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE published_at IS NULL").query(Long.class).single();
    }
}
