-- Transactional outbox: an event is written in the SAME transaction as the state change it
-- describes, so "state changed" and "event recorded" are atomic. A relay publishes the rows to
-- Kafka afterwards and marks them published.
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- publish order
    message_id   UUID        NOT NULL DEFAULT gen_random_uuid() UNIQUE, -- consumers deduplicate on this
    aggregate_id UUID        NOT NULL,                           -- reservation id = Kafka message key
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ                                     -- NULL until the relay has sent it
);

-- The relay only ever looks at unpublished rows: partial indexes stay small.
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
-- "Is there an older unpublished event for the same reservation?" (per-reservation ordering).
CREATE INDEX ix_outbox_unpublished_aggregate ON outbox (aggregate_id, id) WHERE published_at IS NULL;

-- Consumer-side deduplication ("inbox"). Delivery is at-least-once, so the same message can
-- arrive twice; a consumer records the message id in the same transaction as its effect and
-- skips messages it has already recorded.
CREATE TABLE consumer_inbox (
    consumer     TEXT        NOT NULL,
    message_id   UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, message_id)
);

-- Effect of the example consumer: one notification per event. Deliberately NO unique constraint
-- on message_id: deduplication must come from the inbox, and tests prove it does.
CREATE TABLE notifications (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    message_id     UUID        NOT NULL,
    reservation_id UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    outbox_id      BIGINT      NOT NULL, -- position in the outbox, used to detect out-of-order delivery
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_reservation ON notifications (reservation_id);
