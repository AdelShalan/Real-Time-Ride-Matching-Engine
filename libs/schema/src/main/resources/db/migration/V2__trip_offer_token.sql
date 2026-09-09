-- Records which Redis claim reserved the driver on a trip.
--
-- Without this the claim can only ever be handed back by TTL expiry, and markOnTrip() calls
-- PERSIST on the claim marker — so a driver who starts a trip has no TTL left to expire and
-- would be stranded RESERVED forever. Releasing requires the token: the release script
-- compares it against the marker so a worker whose claim already lapsed cannot delete the
-- claim of whoever legitimately took the driver afterwards.
--
-- Nullable, because a trip only holds one between OFFERED and its terminal state. Every
-- existing row is REQUESTED, so there is nothing to backfill.
--
-- No new index accompanies this. Lookups by driver are already served by
-- uniq_driver_active_trip, which is a unique index on (driver_id) over exactly the states
-- that hold one — a second index on the same column and predicate would be dead weight the
-- planner never chooses.
ALTER TABLE trips ADD COLUMN offer_token TEXT;

COMMENT ON COLUMN trips.offer_token IS
    'Opaque token of the Redis claim holding driver_id; required to release that claim.';
