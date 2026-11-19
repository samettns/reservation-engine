-- Fencing token for idempotency claims.
--
-- A claim is a lease. A SLOW owner (GC pause, slow DB) can outlive its lease while another request
-- takes the key over; when the old owner wakes up it must not act as the owner any more. Every
-- takeover increments fence_token; the owner remembers the token it got, and every write that
-- relies on ownership (start of the reservation transaction, COMPLETED, release) requires
-- fence_token = <my token>. A stale owner's token no longer matches, so its writes do nothing and
-- its reservation transaction is rolled back before it touches stock.
ALTER TABLE idempotency_keys ADD COLUMN fence_token BIGINT NOT NULL DEFAULT 1;
