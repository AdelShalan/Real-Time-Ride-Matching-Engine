# System Design: Real-Time Ride-Matching Engine

> Companion to the [README](../README.md). This document is the full design: components, data
> models, algorithms, failure modes, and capacity reasoning.

---

## 1. Problem Statement

Build the dispatch core of a ride-hailing platform:

1. Continuously ingest GPS positions from thousands of moving drivers.
2. When a rider requests a trip, find nearby available drivers within seconds.
3. Assign exactly one driver to exactly one ride — **never** two.
4. Keep downstream concerns (notifications, billing) off the request path.
5. Prove all of the above under load, with metrics.

### Non-goals

Deliberately out of scope, so the design stays focused:

- Routing / ETA from a real road network (a haversine + average-speed proxy is used instead)
- Surge pricing, driver payouts, real payment processing
- Authentication beyond a stubbed bearer token
- Multi-region replication
- Any user interface

---

## 2. Capacity Model

The design targets a single mid-sized city. Numbers drive the architecture; they are not decoration.

| Quantity | Value | Derivation |
|---|---|---|
| Active drivers | 10,000 | Target load |
| Location frequency | 1 Hz | Standard for dispatch systems |
| **Location writes** | **10,000/s** | 10k × 1 Hz |
| Location payload | ~120 bytes | id, lat, lng, heading, speed, ts |
| **Ingest bandwidth** | ~1.2 MB/s | 10k × 120 B |
| Ride requests | 500/s peak | ~1 request per 20 drivers/s |
| GEOSEARCH per ride | 1–3 | Expanding-radius retries |
| Redis memory | ~50 MB | 10k geo entries + state hashes, tiny |
| Postgres write rate | ~1,500/s | Trips + state transitions + outbox |

**The critical observation:** location writes outnumber ride writes by **20:1**, and location data is
disposable — only the newest position matters. Ride data is durable, transactional, and auditable.
These two workloads have opposite requirements, so they get different stores. This split is the
single most important decision in the system.

---

## 3. Component Design

### 3.1 Location Service — WebSocket ingestion

**Responsibility:** terminate driver WebSocket connections, validate frames, and land positions in
the geospatial index with minimal latency.

**Connection model.** Java 21 virtual threads: one virtual thread per connection is affordable at
10k connections (~1 KB stack each initially) where 10k platform threads would not be. This gives
straight-line blocking code — readable and debuggable — with reactive-grade scalability.
See [ADR-0006](adr/0006-java21-virtual-threads.md).

**Frame protocol** (JSON; Protobuf is a documented upgrade path):

```json
{ "t": "loc", "driverId": "d-8842", "lat": 30.0444, "lng": 31.2357,
  "heading": 274.5, "speedKph": 38.2, "ts": 1735689600123 }
```

**Write path, per frame:**

1. **Validate** — driver ID is authenticated on the connection, not trusted from the frame;
   coordinates bounds-checked; timestamp rejected if skewed more than 30 s.
2. **Throttle** — skip the write if the driver has moved less than 25 m *and* less than 3 s have
   passed since their last persisted position. On typical urban movement this cuts Redis writes by
   roughly 40–60% at no loss of matching quality.
3. **Batch** — frames accumulate in a bounded ring buffer per shard; a flusher drains it every 50 ms
   or at 500 entries into a single Redis pipeline. This turns 10,000 round trips/s into ~20 pipelined
   batches/s per shard.
4. **Backpressure** — the buffer is bounded and **drop-oldest**. A stale position has no value, so
   shedding it is correct behavior, not data loss. Drops are counted in
   `location_frames_dropped_total`; a non-zero rate is an alert, not a silent failure.
5. **Mirror** — the frame is also produced to `driver.location.v1` with `acks=1`, `linger.ms=20`,
   lz4 compression. Failures here are logged and dropped; telemetry must never block dispatch.

**Disconnection handling.** On socket close, the driver is marked `OFFLINE` and removed from the geo
index. A separate reaper sweeps a `ZSET` of `lastSeenAt` every 10 s and evicts any driver silent for
more than 30 s, covering half-open connections that never emit a close frame.

### 3.2 Dispatch API — request intake

**Responsibility:** accept ride requests, enforce idempotency, and hand off. It performs **no
matching** — it is deliberately thin so its latency is predictable.

```
POST /v1/rides
  Idempotency-Key: 8f14e45f-ea6a-4f2b-9f22-7c1e2b3a4d5e   (required)
  { "riderId": "...", "pickup": {...}, "dropoff": {...}, "vehicleClass": "STANDARD" }
  -> 202 Accepted { "rideId": "...", "status": "REQUESTED", "pollUrl": "/v1/rides/{id}" }

GET  /v1/rides/{rideId}          current state + assigned driver
POST /v1/rides/{rideId}/cancel   rider-initiated cancellation
POST /v1/drivers/{id}/offers/{offerId}/accept | /decline
GET  /v1/drivers/nearby?lat=&lng=&radiusKm=   debug/inspection endpoint
```

Returning `202` rather than blocking until a match is the key latency decision: the API's p99 stays
in the tens of milliseconds regardless of how long matching takes, and matching load can never cause
request timeouts. Clients poll `GET /v1/rides/{id}` or subscribe to a rider WebSocket for push.

### 3.3 Matching Engine

**Responsibility:** turn a `ride.requested` event into a driver assignment.

Runs as a Kafka consumer group. Partition count (12) sets the maximum useful parallelism; each
partition is processed in order, and requests for different rides are independent, so scaling out is
adding instances until partitions run out.

**Algorithm per request:**

```
for radiusKm in [2.0, 3.2, 5.0]:                     # expanding search
    candidates = GEOSEARCH geo:drivers:{city}
                   FROMLONLAT lng lat BYRADIUS radiusKm km ASC COUNT 30 WITHCOORD WITHDIST

    candidates = filter(candidates, status == AVAILABLE
                                 and vehicleClass matches
                                 and lastSeenAt within 30s
                                 and not in rider's decline list)

    rank candidates by score (below)

    for driver in ranked[:5]:
        claim = redis.eval(CLAIM_LUA, driver.id, offerToken, ttl=10s)
        if claim.denied:                              # lost the race
            metrics.claimContention.inc()
            continue
        trip = tripService.assign(rideId, driver.id, claim.fence)   # DB is authority
        if trip.rejected:                             # unique index fired
            redis.release(driver.id, offerToken)
            continue
        offer = dispatchOffer(driver, ttl=8s)
        await offer.outcome                           # ACCEPTED | DECLINED | EXPIRED
        if ACCEPTED: publish ride.matched.v1; return
        else: release claim; record decline; continue

    sleep(backoff)                                    # let the driver pool refresh

publish ride.unmatched.v1                             # 30s SLA exhausted
```

**Scoring.** Nearest-by-line-distance is a weak proxy for nearest-by-time, so candidates are ranked
on a composite:

```
score = w1 * normalizedEtaSeconds          # haversine / class-average speed
      + w2 * (1 - driverAcceptanceRate)    # historically flaky drivers cost more
      + w3 * headingMismatchPenalty        # a driver pointing away must turn around
      + w4 * idleTimePenalty               # fairness: favor the longest-waiting driver
```

Weights are configuration, not constants in code, so the trade-off between rider wait time and driver
fairness is tunable without redeploying.

**Documented extension:** the greedy loop above is locally optimal per request. Under high demand
density, batching requests into 2-second windows and solving the assignment as a bipartite matching
problem (Hungarian algorithm) measurably reduces mean pickup time. Noted as a future benchmark rather
than claimed as implemented.

### 3.4 Trip Service

Owns the trip state machine and Postgres. Every transition is validated against an explicit
transition table; an illegal transition throws rather than being coerced. Each successful transition
writes a `trip_events` row (append-only audit log) and an `outbox` row in the **same transaction** as
the trip update.

### 3.5 Notification & Billing Services

Two separate consumer groups on the same topics. They exist to *prove* decoupling: killing either one
must not affect matching latency at all, and on restart they replay from their committed offset with
no lost work. The load test explicitly includes a "kill the notification service mid-run" step.

---

## 4. Data Design

### 4.1 Redis key space

| Key | Type | Contents | TTL |
|---|---|---|---|
| `geo:drivers:{city}` | GEO (zset) | member = driverId, score = geohash | — |
| `driver:{id}:state` | Hash | `status`, `vehicleClass`, `lastSeenAt`, `tripId`, `fence` | 60 s, refreshed |
| `driver:seen:{city}` | ZSET | score = lastSeenAt epoch ms — reaper input | — |
| `claim:{driverId}` | String | offerToken of the current holder | 10 s |
| `fence:counter` | String | monotonic `INCR` source for fencing tokens | — |
| `offer:{offerId}` | Hash | rideId, driverId, expiresAt | 15 s |

`{city}` is a hash tag so all keys for a city land on one Redis Cluster slot, keeping `GEOSEARCH`
single-node and Lua scripts (which cannot span slots) valid.

**Driver status:** `OFFLINE → AVAILABLE → RESERVED → ON_TRIP → AVAILABLE`. `RESERVED` is the
short-lived state between winning a claim and the driver accepting or the offer expiring.

### 4.2 The claim script (`claim.lua`)

```lua
-- KEYS[1] = driver:{id}:state   KEYS[2] = claim:{id}   KEYS[3] = fence:counter
-- ARGV[1] = offerToken          ARGV[2] = ttlMillis
if redis.call('HGET', KEYS[1], 'status') ~= 'AVAILABLE' then
  return {0, 'NOT_AVAILABLE'}
end
if redis.call('EXISTS', KEYS[2]) == 1 then
  return {0, 'ALREADY_CLAIMED'}
end
local fence = redis.call('INCR', KEYS[3])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
redis.call('HSET', KEYS[1], 'status', 'RESERVED', 'fence', fence)
return {1, fence}
```

Redis runs Lua atomically on its single command thread: no other client observes or mutates the state
between the check and the set. This is what makes the fast path correct without a lock protocol.

### 4.3 PostgreSQL schema (essentials)

```sql
CREATE TABLE trips (
    id             UUID PRIMARY KEY,
    rider_id       UUID NOT NULL,
    driver_id      UUID,
    status         trip_status NOT NULL,
    fence_token    BIGINT,
    pickup         GEOGRAPHY(POINT, 4326) NOT NULL,
    dropoff        GEOGRAPHY(POINT, 4326) NOT NULL,
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    matched_at     TIMESTAMPTZ,
    completed_at   TIMESTAMPTZ,
    version        INT NOT NULL DEFAULT 0   -- optimistic locking
);

-- THE correctness guarantee: one active trip per driver, enforced by the database.
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id)
    WHERE status IN ('MATCHED', 'ACCEPTED', 'IN_PROGRESS');

CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,
    request_hash    TEXT        NOT NULL,
    state           TEXT        NOT NULL,   -- IN_PROGRESS | COMPLETED
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE outbox (
    id           BIGSERIAL PRIMARY KEY,
    aggregate_id UUID        NOT NULL,
    topic        TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE trip_events (          -- append-only audit trail
    id         BIGSERIAL PRIMARY KEY,
    trip_id    UUID NOT NULL REFERENCES trips(id),
    from_state trip_status,
    to_state   trip_status NOT NULL,
    reason     TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`uniq_driver_active_trip` is the line that makes the headline claim true. Everything in Redis is an
optimization layered on top of it. If a reviewer only reads one line of SQL in this repository, this
is the one.

**Pessimistic locking**, where the brief calls for it, is used for driver-initiated accept/decline:
`SELECT ... FROM trips WHERE id = ? FOR UPDATE` serializes concurrent accept and cancel on the same
trip. It is the right tool for a short, contended, single-row transaction — and the wrong tool for
the matching search, which is why matching uses the Redis fast path instead.

---

## 5. Failure Modes

Designing for the happy path is not a design. Each row is exercised by an integration test.

| Failure | Effect | Mitigation |
|---|---|---|
| Redis dies | No geo index; matching halts | Postgres constraint still prevents corruption. API stays up returning `503` for matching; drivers reconnect and repopulate the index in ~1 s of frames. AOF `everysec` for warm restart. |
| Claim lease expires mid-assignment | Another worker may claim the same driver | Fencing token: the stale worker's DB write carries an older token and is rejected; the unique index is the backstop. |
| Kafka unavailable | Ride requests cannot be enqueued | API returns `503` with `Retry-After` rather than accepting work it cannot process. Location mirroring degrades silently by design. |
| Consumer crash mid-processing | Event redelivered | Consumers are idempotent on event ID; assignment is naturally idempotent via the unique index. |
| Postgres primary failover | Writes fail for a few seconds | Bounded retries with jittered backoff; the outbox guarantees nothing published is lost. |
| Driver goes offline while `RESERVED` | Ride would stall | Offer TTL (8 s) expires, claim is released, matching resumes with the next candidate. |
| Ghost driver (half-open socket) | Matched to an unreachable driver | 30 s heartbeat reaper evicts them; offer expiry catches the narrow window. |
| Poison message | Consumer stuck in a redelivery loop | 3 retries with backoff, then routed to `*.dlt` with full context; consumer advances. |
| Thundering herd on reconnect | Ingest spike after a deploy | Client-side jittered reconnect plus a connection rate limiter at the edge. |

---

## 6. Testing Strategy

| Layer | Scope | Tooling |
|---|---|---|
| Unit | Domain invariants, state machine, scoring — no infra | JUnit 5, AssertJ |
| Integration | Real Redis / Postgres / Kafka per test class | Testcontainers |
| **Concurrency** | 200 threads race for 1 driver; assert exactly 1 wins | JUnit + `CountDownLatch` barrier |
| **Idempotency** | 50 parallel retries of one key produce 1 ride | Testcontainers |
| Contract | Event schemas are backward compatible | Schema compatibility check in CI |
| Resilience | Kill Redis / Kafka mid-run, assert recovery | Testcontainers + `docker pause` |
| Load | Full-stack, 10k drivers + 5k riders | k6 + simulator |

The concurrency test is the flagship: a `CountDownLatch` releases N threads simultaneously against a
single available driver, then asserts `SELECT count(*) FROM trips WHERE driver_id = ? AND status IN
(...)` equals 1 and that exactly one thread received a success. It is a test that fails loudly on any
regression in the claim path.

---

## 7. Roadmap

| Phase | Deliverable |
|---|---|
| 0 | Design docs, ADRs, architecture diagram ✅ |
| 1 | Docker Compose infra + Maven multi-module skeleton + health checks |
| 2 | Domain model, trip state machine, unit tests |
| 3 | Location Service: WebSocket ingestion → Redis GEO |
| 4 | Dispatch API + idempotency + Postgres schema |
| 5 | Matching Engine + claim protocol + concurrency tests |
| 6 | Kafka topology, outbox publisher, notification/billing consumers |
| 7 | Prometheus + Grafana dashboards |
| 8 | Simulator + k6 load harness, published results |
