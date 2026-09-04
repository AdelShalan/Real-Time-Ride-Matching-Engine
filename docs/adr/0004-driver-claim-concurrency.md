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

### Pessimistic locking, where it fits

`SELECT ... FOR UPDATE` **is** used — for driver accept/decline and rider cancel, where concurrent
transactions contend on a single known row and the operation is short. It is the right tool there and
the wrong tool for matching, where the contended resource is discovered by a search rather than known
in advance.

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

- Two mechanisms to keep in sync. Redis state can drift from Postgres, so a reconciliation job sweeps
  for drivers marked `RESERVED`/`ON_TRIP` in Redis with no active trip in Postgres and repairs them.
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

`DriverClaimIT` assertions:

- exactly one thread receives a successful assignment;
- `SELECT count(*) FROM trips WHERE driver_id = ? AND status IN ('OFFERED','ACCEPTED','IN_PROGRESS')`
  returns 1;
- the 199 losers each report `ALREADY_CLAIMED` or a constraint rejection, and none throw an unhandled
  exception.

The same assertion runs as a post-condition of every load test.
