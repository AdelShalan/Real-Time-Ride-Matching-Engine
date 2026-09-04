-- Initial schema for the ride-matching engine.
--
-- Schema ownership note: these migrations live in dispatch-api because it is currently the
-- only service that touches PostgreSQL. When trip-service takes over the trip lifecycle it
-- inherits them; two services running Flyway against one database independently is a race,
-- not a design.
--
-- No PostGIS yet, deliberately. ADR-0002 keeps PostGIS for geofences and zone analytics, and
-- none of those queries exist. Plain numeric columns hold the coordinates until there is a
-- query that needs a spatial index; adding an extension before it does costs an image change
-- and buys nothing.

CREATE TYPE trip_status AS ENUM (
    'REQUESTED',
    'MATCHING',
    'OFFERED',
    'ACCEPTED',
    'IN_PROGRESS',
    'COMPLETED',
    'CANCELLED',
    'UNMATCHED'
);

CREATE TYPE vehicle_class AS ENUM ('STANDARD', 'XL', 'PREMIUM');

CREATE TABLE trips (
    id             UUID PRIMARY KEY,
    rider_id       UUID          NOT NULL,
    driver_id      UUID,
    status         trip_status   NOT NULL,
    vehicle_class  vehicle_class NOT NULL,

    -- Fencing token from the Redis claim (ADR-0004). Null until a driver is offered.
    fence_token    BIGINT,

    pickup_lat     DOUBLE PRECISION NOT NULL,
    pickup_lng     DOUBLE PRECISION NOT NULL,
    dropoff_lat    DOUBLE PRECISION NOT NULL,
    dropoff_lng    DOUBLE PRECISION NOT NULL,

    requested_at   TIMESTAMPTZ   NOT NULL,
    matched_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,

    -- Optimistic locking for the short, contended updates (accept/decline/cancel).
    version        INTEGER       NOT NULL DEFAULT 0,

    CONSTRAINT trips_driver_required_when_held
        CHECK (status NOT IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS') OR driver_id IS NOT NULL),
    CONSTRAINT trips_pickup_lat_range  CHECK (pickup_lat  BETWEEN -90  AND 90),
    CONSTRAINT trips_pickup_lng_range  CHECK (pickup_lng  BETWEEN -180 AND 180),
    CONSTRAINT trips_dropoff_lat_range CHECK (dropoff_lat BETWEEN -90  AND 90),
    CONSTRAINT trips_dropoff_lng_range CHECK (dropoff_lng BETWEEN -180 AND 180)
);

-- =====================================================================================
-- THE correctness guarantee (ADR-0004).
--
-- A driver can appear in at most one row whose status holds them. Redis claims are a
-- contention optimiser; this index is what makes double-assignment physically impossible.
-- It survives Redis loss, an expired lease, a network partition, and a GC pause.
--
-- The predicate must stay identical to TripStatus.holdsDriver() in the domain module.
-- A state the domain treats as holding a driver but the index ignores is a hole in the
-- guarantee; TripStatusTest asserts the two agree.
-- =====================================================================================
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id)
    WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS');

CREATE INDEX idx_trips_rider_requested_at ON trips (rider_id, requested_at DESC);
CREATE INDEX idx_trips_status ON trips (status) WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'UNMATCHED');

-- Append-only audit trail: the in-memory Trip.history() made durable.
CREATE TABLE trip_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trip_id     UUID        NOT NULL REFERENCES trips (id),
    from_status trip_status,
    to_status   trip_status NOT NULL,
    driver_id   UUID,
    reason      TEXT,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_trip_events_trip ON trip_events (trip_id, occurred_at);

-- =====================================================================================
-- Idempotency (ADR-0005).
--
-- The primary key is the arbitration mechanism: two simultaneous retries both attempt the
-- insert and exactly one wins. A read-then-write check would have a window between the two
-- where both see "not found".
-- =====================================================================================
CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,

    -- SHA-256 of the canonical request body. Catches a client reusing one key for a
    -- different request, which must be a loud error rather than a silent replay.
    request_hash    TEXT        NOT NULL,

    state           TEXT        NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    ride_id         UUID,
    response_status INTEGER,
    response_body   JSONB,

    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL
);

-- Supports the pruning job; partial so it stays small.
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);

-- =====================================================================================
-- Transactional outbox (ADR-0003).
--
-- Written in the SAME transaction as the state change it describes, so "the trip is saved"
-- and "the event will be published" commit together. A publisher drains it to Kafka.
-- =====================================================================================
CREATE TABLE outbox (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    event_id     UUID        NOT NULL UNIQUE,
    topic        TEXT        NOT NULL,
    partition_key TEXT       NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);

-- Partial index: the publisher only ever scans unpublished rows, so the index stays the
-- size of the backlog rather than the size of history.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
