-- Version counter for the optimistic locking strategy. Every successful write
-- increments it; a writer that read an older version must retry.
-- (This is exactly what JPA's @Version does behind the scenes.)
ALTER TABLE stock ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
