# ADR-0005: Idempotency keys on ride creation

**Status:** Accepted · **Date:** 2026-09-03

## Context

`POST /v1/rides` is not naturally idempotent — two calls create two rides. But the network makes
retries inevitable: a client times out after the server committed, a mobile app retries on a flaky
connection, a load balancer replays a request. Without protection, a rider gets charged for two trips
and two drivers are dispatched to one pickup.

The subtle part is that the retry may arrive **while the original is still in flight**, so a
read-then-write check has a window between the read and the write in which both requests see "no key
found".

## Decision

Require an `Idempotency-Key` header (client-generated UUID) on all ride-creating requests, backed by
a Postgres table where the primary key does the arbitration.

```sql
CREATE TABLE idempotency_keys (
    key             TEXT PRIMARY KEY,
    request_hash    TEXT        NOT NULL,   -- SHA-256 of the canonical request body
    state           TEXT        NOT NULL,   -- IN_PROGRESS | COMPLETED
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL
);
```

**Algorithm**

```
INSERT INTO idempotency_keys (key, request_hash, state, expires_at)
VALUES (?, ?, 'IN_PROGRESS', now() + interval '24 hours')
ON CONFLICT (key) DO NOTHING;
```

- **Insert succeeded** → this is the first request. Process it, then update the row to `COMPLETED`
  with the response status and body **in the same transaction as the ride creation**.
- **Insert conflicted** → read the existing row:

| Existing row | Response |
|---|---|
| `request_hash` differs | `422 Unprocessable Entity` — the same key was reused for a different request; that is a client bug and must be loud |
| `state = IN_PROGRESS` | `409 Conflict` + `Retry-After: 1` — the original is still running |
| `state = COMPLETED` | Replay the **stored** `response_status` and `response_body` verbatim |

Expired keys are pruned by a scheduled job; a retry after 24 hours is treated as a new request.

## Rationale

- **The database primary key resolves the race.** Two simultaneous retries both attempt the insert;
  exactly one wins. There is no window, because there is no separate read step.
- **Storing the response, not just the key,** means a retry gets the *same* `rideId` back rather than
  a generic "already processed" — which is what a client actually needs to continue.
- **Hashing the body** catches the dangerous mistake of a client reusing a key across different
  requests. Silently returning the first response would be worse than an error.
- **Committing the key state with the ride** keeps the two from diverging. If the ride commits and
  the key update does not, the retry re-executes and creates a duplicate ride — the exact bug being
  prevented.
- Postgres, not Redis, because this is a correctness mechanism and must survive a cache flush.

## Consequences

**Positive**

- Network retries, LB replays, and impatient client re-taps cannot create duplicate rides.
- The semantics match Stripe's widely understood idempotency contract, so the API behaves the way an
  experienced integrator expects.
- `409` vs `422` gives clients a machine-readable distinction between "wait" and "you have a bug".

**Negative**

- One extra write per ride request. Negligible next to the ride insert itself.
- The key table grows and needs pruning.
- Clients must generate and persist a key across retries. Documented in the API reference; the
  simulator and k6 scenarios do this correctly, including a deliberate duplicate-retry scenario.

## Verification

`IdempotencyIT`: 50 threads fire the identical `(key, body)` simultaneously. Assertions — exactly one
ride row exists, every response carries the same `rideId`, and responses are drawn only from
`{202, 409, 200}`.
