# ADR-0004: Two-layer driver claim — and why not Redlock

**Status:** Accepted · **Date:** 2026-09-03

## Context

The system's central invariant: **a driver is assigned to at most one active ride.**

Under load, multiple matching workers on different instances routinely rank the same driver first —
the nearest available driver to two riders one block apart is the same person. Between the
`GEOSEARCH` that returns a driver and the write that assigns them, another worker can do the same
thing. The window is milliseconds, which means it *will* be hit at 500 requests/sec.

The brief suggests Redlock or database pessimistic locking. Both were evaluated.

## Decision

**Two layers with different jobs.** The distributed lock is a performance optimization; the database
constraint is the correctness guarantee.

### Layer 1 — Redis Lua compare-and-set (contention control)

```lua
if redis.call('HGET', KEYS[1], 'status') ~= 'AVAILABLE' then return {0, 'NOT_AVAILABLE'} end
if redis.call('EXISTS', KEYS[2]) == 1 then return {0, 'ALREADY_CLAIMED'} end
local fence = redis.call('INCR', KEYS[3])
redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[2])
redis.call('HSET', KEYS[1], 'status', 'RESERVED', 'fence', fence)
return {1, fence}
```

Redis executes Lua atomically on a single thread — no other client can observe or mutate the state
between the check and the set. The loser is told in one round trip and moves to its next-best
candidate immediately, rather than blocking, spinning, or retrying the same driver.

### Layer 2 — PostgreSQL unique partial index (correctness)

```sql
CREATE UNIQUE INDEX uniq_driver_active_trip
    ON trips (driver_id)
    WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS');
```

The database physically cannot store two active trips for one driver. Redis can lose its dataset, a
lease can expire mid-flight, the network can partition, a worker can be paused by a 4-second GC — and
the invariant still holds. A violating insert raises a constraint error, the worker releases its
claim and moves to the next candidate. **Correctness lives where the durable state lives.**

### Fencing tokens

Every claim carries a monotonic token from `INCR`. The assignment write includes it; a worker that
stalled past its lease and wakes up late presents a token lower than the current one and is rejected.
This closes the failure that lease-based locks silently permit: the lock expires while its holder is
still working and still believes it holds the lock.

### Releasing the claim

A claim is a driver withdrawn from the pool, so the lifecycle has to give them back. Every terminal
transition releases, and the release is token-checked in Lua for the same reason the claim is: a
worker whose claim already lapsed must not be able to delete the marker of whoever legitimately took
the driver afterwards.

**The release happens after the Postgres commit, never before.** Postgres is the boundary, so the
durable write is the one that decides. Releasing first would briefly advertise a driver as available
while a committed row still held them — not a correctness problem, since the index would reject the
competing claim, but it converts a cheap Redis rejection into an expensive database one, which is the
exact trade the fast path exists to avoid.

That ordering leaves a window, and the window is not benign: acceptance calls `PERSIST` on the claim
marker (an offer TTL measured in seconds would otherwise expire mid-journey and have the engine
offering rides to a driver already carrying a passenger), so a crash between the commit and the
release strands that driver with no TTL to rescue them. The reconciler below closes it.

### Optimistic locking, not pessimistic

Lifecycle transitions — accept, start, complete, cancel — use a `version` column and an
`UPDATE ... WHERE version = :expected`, not `SELECT ... FOR UPDATE`. The contended window is
microseconds and the collision rate is low, so taking a row lock on every read would cost more than
the rare rejected write it saves. What matters is that a collision is *detected*: a last-writer-wins
update would let a driver's acceptance silently overwrite a rider's cancellation, which is the same
class of bug as double-assignment wearing different clothes. A losing writer gets `409` with
`Retry-After` and decides again, because the right action genuinely depends on what the winner did.

Pessimistic locking remains the wrong tool for matching itself, where the contended resource is
discovered by a search rather than known in advance.

## Why not Redlock

Redlock (locking a majority of N independent Redis nodes) is the textbook answer and is rejected
deliberately:

1. **Its safety argument depends on assumptions this system cannot make.** Redlock is safe only under
   bounded clock drift and bounded process pauses. A JVM stop-the-world GC pause longer than the
   lease breaks it, and JVM pauses of hundreds of milliseconds are ordinary. Kleppmann's critique
   ("How to do distributed locking", 2016) is the reference; Antirez's response does not resolve the
   pause case for lock holders performing external writes.
2. **A lock cannot make an external write safe on its own.** Whatever the lock protocol, the
   assignment write to Postgres needs to be rejected if the holder's lease has lapsed — which
   requires fencing tokens at the resource. Once the resource validates fencing tokens, the elaborate
   multi-node acquisition adds cost without adding a guarantee.
3. **Multi-node Redis is real operational cost** for a guarantee already provided by a single unique
   index.

Using a simple single-Redis CAS for the fast path and being explicit that it is *not* the safety
mechanism is the more defensible engineering position than deploying Redlock and treating its
guarantee as absolute.

## Consequences

**Positive**

- The invariant holds under Redis loss, network partition, worker pause, and duplicate Kafka
  delivery — all of which are covered by integration tests.
- Contention resolution costs one Redis round trip; losers make progress on a different driver
  instead of queueing.
- `driver_claim_contention_total` makes contention an observable quantity rather than a mystery.

**Negative**

- Two mechanisms to keep in sync. Redis state can drift from Postgres, so `ClaimReconciler` sweeps
  every 30 s for claims Postgres says no trip is holding and releases them. It syncs Redis *from*
  Postgres and never the reverse — a reconciler that trusted Redis could delete a live assignment.
  It considers only claims with **no TTL**, which removes the race this kind of sweep usually has:
  an untimed claim can only come from `markOnTrip`, which runs after an acceptance commits, whereas
  a claim still carrying a TTL may be an offer whose `ride.matched.v1` has not been consumed yet —
  no trip row justifies it, and freeing it would take the driver from a match about to land.
- The unique index serializes concurrent inserts for the same `driver_id`, so a pathological
  hot-driver scenario is bounded by Postgres. Acceptable: the Redis layer means this path is rarely
  reached.
- Constraint-violation exceptions are normal control flow here, not errors. They are counted, not
  logged at `ERROR`, so the signal is not drowned out.

## Verification

**Measured 2026-09-04.** `DriverClaimIT`: 200 virtual threads held at a `CountDownLatch` are
released simultaneously against a single `AVAILABLE` driver.

| Implementation | Winners out of 200 |
|---|---|
| Lua script (atomic check-and-set) | **1** |
| Naive check-then-set, two round trips | **200** |

The second row is the point. Replacing the script with the obvious implementation — read the
status, then write the reservation — does not produce an occasional double-assignment that a
retry might paper over. **Every single thread wins**, because all 200 read `AVAILABLE` before
any of them writes `RESERVED`. Under real load that is 200 riders dispatched to one driver.

This was verified by deliberately breaking the implementation and confirming the test fails, then
restoring it. A concurrency test that has never been seen to fail is decoration.

**Measured 2026-09-09**, when the lifecycle was wired up. Two more guards, falsified the same way:

| Guard removed | Result |
|---|---|
| `releaseClaim` after a terminal transition | 2 failures — the driver stays `RESERVED` after the trip completes |
| `AND version = :expectedVersion` on the lifecycle update | 32 concurrent accepts of one trip, **all 32 win** |

The second is the same shape as the table above and worth the same attention. Without the version
predicate the race does not produce an occasional lost update; every thread reads version 0, every
`UPDATE` matches, and one trip records thirty-two acceptances.

`DriverClaimIT` assertions:

- exactly one thread receives a successful assignment;
- `SELECT count(*) FROM trips WHERE driver_id = ? AND status IN ('OFFERED','ACCEPTED','IN_PROGRESS')`
  returns 1;
- the 199 losers each report `ALREADY_CLAIMED` or a constraint rejection, and none throw an unhandled
  exception.

The same assertion runs as a post-condition of every load test.
