# ADR-0003: Kafka topology, at-least-once delivery, and the transactional outbox

**Status:** Accepted · **Date:** 2026-09-03

## Context

Matching, notification, and billing must be decoupled from the rider's request. The naive
implementation — publish to Kafka inside the same method that writes to Postgres — contains a silent
correctness bug: the two systems cannot commit atomically. Either

- publish first, DB write fails → a `ride.matched` event exists for a trip that does not, or
- commit first, publish fails → the trip is matched but nobody is ever notified.

Both are dual-write failures, and both are invisible until they happen in production.

## Decision

1. **At-least-once delivery**, explicitly. Producers use `acks=all` with idempotent producer enabled;
   consumers commit offsets *after* processing and are made idempotent by de-duplicating on event ID
   (a `processed_events` table, or Redis `SET NX` with TTL for cheap consumers).
2. **Transactional outbox** for every state-changing event. The trip row and the outbox row are
   written in one local transaction; a poller drains unpublished outbox rows to Kafka and stamps
   `published_at`.
3. **Topic layout** keyed for ordering:

| Topic | Key | Partitions | Retention | Producer acks |
|---|---|---|---|---|
| `driver.location.v1` | `driverId` | 12 | 1 h, lz4 | `1` (telemetry, may drop) |
| `ride.requested.v1` | `rideId` | 12 | 7 d | `all` |
| `ride.matched.v1` | `rideId` | 12 | 7 d | `all` |
| `ride.unmatched.v1` | `rideId` | 6 | 7 d | `all` |
| `ride.completed.v1` | `rideId` | 12 | 7 d | `all` |
| `*.dlt` | original key | 3 | 30 d | `all` |

4. **Version in the topic name** (`.v1`). A breaking schema change is a new topic with a
   dual-publishing window, not a coordinated big-bang deploy.
5. **Dead-letter topics.** 3 retries with exponential backoff, then the record goes to `<topic>.dlt`
   with the exception and stack trace in headers, and the consumer advances. One poison message
   cannot stall a partition.

## Rationale

Keying by `rideId` guarantees all events for one ride land on one partition and are therefore
strictly ordered — `requested → matched → completed` can never be observed out of order. Keying
location by `driverId` gives the same guarantee per driver. Partition count (12) is set above the
expected consumer instance count so the system can scale out without repartitioning.

**Exactly-once is not claimed.** Kafka transactions provide EOS only within Kafka
(consume-transform-produce); the moment a consumer writes to Postgres or sends a push notification,
the guarantee ends. Idempotent consumers over at-least-once delivery is the design that is actually
correct end-to-end, and saying so is more valuable than an EOS badge that does not hold.

## Consequences

**Positive**

- No dual-write bug. The outbox makes "the trip is saved" and "the event will be published" the same
  commit.
- Notification and billing can be stopped, deployed, or crash without touching matching latency —
  demonstrated explicitly in the load test by killing a consumer mid-run.
- Consumer lag becomes the single clearest backpressure metric in the dashboard.

**Negative**

- The outbox poller adds latency (poll interval, default 200 ms) between commit and publish. Debezium
  CDC would remove it at the cost of significant operational complexity; not worth it here.
- The outbox table needs periodic pruning of published rows.
- Every consumer must carry de-duplication logic — real code that must be tested, not a config flag.
