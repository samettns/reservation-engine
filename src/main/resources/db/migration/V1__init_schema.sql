-- An event (concert, flash sale...) with a fixed amount of stock.
-- total_stock never changes after creation; it is the reference value for the
-- invariant  reserved + remaining = total_stock.
CREATE TABLE events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,
    total_stock INT         NOT NULL CHECK (total_stock >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Remaining stock lives in its own table: this is the "hot row" every request
-- fights over. Keeping it separate from events lets us shard it later (stage 3)
-- without touching the event itself.
CREATE TABLE stock (
    event_id BIGINT PRIMARY KEY REFERENCES events (id),
    -- Last line of defence: the database refuses negative stock no matter what
    -- the application does. (It does NOT prevent lost updates; see the naive strategy.)
    qty      INT NOT NULL CHECK (qty >= 0)
);

CREATE TABLE reservations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        BIGINT      NOT NULL REFERENCES events (id),
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The same client request (user + key) can create at most one reservation,
    -- even if it is retried concurrently. The database enforces this, not the app.
    CONSTRAINT uq_reservations_user_idempotency_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX ix_reservations_event_id ON reservations (event_id);
