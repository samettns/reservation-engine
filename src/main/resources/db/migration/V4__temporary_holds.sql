-- Temporary holds: a reservation first HOLDS a unit until a deadline; payment CONFIRMS it,
-- otherwise it EXPIRES and the unit goes back to the shard it was taken from.
--
--   HELD --(payment before expires_at)--> CONFIRMED
--   HELD --(expires_at passed, sweeper)--> EXPIRED   (+1 stock on the same shard)
--
-- Both transitions are conditional on status = 'HELD', so exactly one of them can win.

ALTER TABLE reservations
    ADD COLUMN shard_no     INT NOT NULL DEFAULT 0, -- where the unit came from; returned to the same shard
    ADD COLUMN expires_at   TIMESTAMPTZ,            -- deadline for payment (set while HELD)
    ADD COLUMN confirmed_at TIMESTAMPTZ,
    ADD COLUMN expired_at   TIMESTAMPTZ;

-- Reservations created before this migration were final immediately.
UPDATE reservations SET confirmed_at = created_at WHERE status = 'CONFIRMED';

ALTER TABLE reservations
    ADD CONSTRAINT ck_reservations_status CHECK (status IN ('HELD', 'CONFIRMED', 'EXPIRED')),
    ADD CONSTRAINT ck_reservations_held_has_deadline CHECK (status <> 'HELD' OR expires_at IS NOT NULL);

-- The sweeper's query: "HELD and past the deadline, oldest first". A partial index stays small
-- because it only contains rows that are still HELD.
CREATE INDEX ix_reservations_due ON reservations (expires_at) WHERE status = 'HELD';
