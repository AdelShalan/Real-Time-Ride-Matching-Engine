# ADR-0002: Redis GEO for the hot path, PostGIS for analytics

**Status:** Accepted · **Date:** 2026-09-03

## Context

10,000 drivers reporting at 1 Hz produce **10,000 writes/sec**, each overwriting the previous value
for that driver. Matching needs a k-nearest-neighbours query within a radius, in single-digit
milliseconds, several hundred times per second.

This is a write-dominated workload over disposable data with a strict read latency budget.

## Decision

**Redis GEO (`GEOADD` / `GEOSEARCH`) is the live index.** PostGIS remains in the schema for
persistent geography — trip pickup/dropoff points, zone polygons, historical analysis — but is never
on the matching path.

## Rationale

| | Redis GEO | PostgreSQL + PostGIS |
|---|---|---|
| Update cost | Sorted-set insert on a 52-bit geohash, O(log N), in memory | Row update + GiST index maintenance + WAL + eventual vacuum |
| 10k writes/s | Comfortable; a pipelined batch is one syscall for hundreds of updates | Sustainable, but generates continuous dead tuples and autovacuum pressure on a table that is 100% churn |
| Radius query | `GEOSEARCH ... BYRADIUS ... ASC COUNT n`, results pre-sorted by distance | `ST_DWithin` + `ORDER BY <->`, correct and flexible, but a planner and buffer-cache round trip |
| Durability | None assumed — and none needed | Full ACID |
| Complex geospatial | Radius and box only | Polygons, joins, arbitrary predicates |

The decisive point is not raw speed, it is **matching the storage model to the data's semantics**: a
driver's position is a *cache of the present*, not a *record of the past*. Writing 864 million rows
per day of data whose only reader wants the newest value is the wrong shape for an ACID store. Losing
the entire Redis index costs one second of driver frames to rebuild.

Trip history — which genuinely is a record of the past — lives in Postgres, and geographic columns
there use PostGIS so that "how many trips started in zone X" stays a SQL query rather than an
application-side loop.

## Consequences

**Positive**

- Sub-millisecond candidate lookups; the matching budget is spent on ranking and claiming, not I/O.
- Postgres write volume drops from ~10,000/s to ~1,500/s, so its connection pool and vacuum settings
  are sized for transactional work only.
- Redis memory is bounded and small: 10k drivers ≈ 50 MB including state hashes.

**Negative**

- Two stores to operate, and a consistency seam between them. Managed by making Postgres
  authoritative for anything durable and treating Redis as reconstructible.
- Radius/box queries only. Non-circular service areas would need a PostGIS pre-filter or an H3 index.
- Redis Cluster requires hash-tagged keys (`geo:drivers:{city}`) so a search and its Lua scripts stay
  within one slot — a real constraint on how the key space can be sharded later.

## Note on the alternative

The `DriverLocationIndex` port ([ADR-0001](0001-hexagonal-architecture.md)) has room for a PostGIS
adapter, and building one to benchmark the two under identical load is on the roadmap. Asserting the
trade-off with numbers is stronger than asserting it from first principles.
