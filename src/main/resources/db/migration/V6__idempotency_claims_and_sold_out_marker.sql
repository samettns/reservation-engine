-- 1) Idempotency claims with an "in progress" state.
--
-- A request claims its (user, idempotency key) BEFORE touching stock, in its own committed
-- transaction. A concurrent twin of the same request then sees IN_PROGRESS and is told so (409),
-- instead of racing for stock and possibly answering "sold out" for a request that succeeded.
-- locked_until is a lease: if the owner crashes, the claim can be taken over after it expires.
-- COMPLETED is written in the same transaction as the reservation itself.
CREATE TABLE idempotency_keys (
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    event_id        BIGINT      NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    reservation_id  UUID,
    locked_until    TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key),
    CONSTRAINT ck_idempotency_completed_has_reservation CHECK (status <> 'COMPLETED' OR reservation_id IS NOT NULL)
);

-- Existing reservations are completed requests.
INSERT INTO idempotency_keys (user_id, idempotency_key, event_id, status, reservation_id, locked_until, created_at)
SELECT user_id, idempotency_key, event_id, 'COMPLETED', id, created_at, created_at FROM reservations;

-- 2) Versioned "sold out" marker for sharded events.
--
-- stock_version is incremented every time units are RETURNED to the event (expired holds).
-- A request that found every shard empty stores the version it observed in sold_out_version
-- (compare-and-set on stock_version). The marker is valid only while both are equal, so any
-- return of stock invalidates it automatically, and a return that happens while the marker is
-- being set makes the compare-and-set fail. Never sold out: -1.
ALTER TABLE events
    ADD COLUMN stock_version    BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN sold_out_version BIGINT NOT NULL DEFAULT -1;
