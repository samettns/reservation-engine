-- Stock sharding: an event's stock can be split across N rows ("shards") so that
-- concurrent reservations update different rows instead of queueing on one hot row.
--
-- Existing events keep a single shard (shard_no = 0), so their behaviour is unchanged.
-- The shard count is fixed when the event is created (resharding live stock is out of scope).

ALTER TABLE events
    ADD COLUMN shard_count INT NOT NULL DEFAULT 1
        CHECK (shard_count BETWEEN 1 AND 256);

ALTER TABLE stock
    ADD COLUMN shard_no INT NOT NULL DEFAULT 0
        CHECK (shard_no >= 0);

ALTER TABLE stock DROP CONSTRAINT stock_pkey;
ALTER TABLE stock ADD PRIMARY KEY (event_id, shard_no);
