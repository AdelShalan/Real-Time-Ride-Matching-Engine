# Load Testing Methodology

> **Status: planned.** This document is the test plan written *before* the implementation, so the
> targets cannot be retrofitted to whatever the code happens to produce. Results tables are marked
> `TBD` and will be filled with real measurements plus Grafana screenshots.

---

## Principles

1. **Publish the harness, not just the numbers.** Everything below is reproducible from this repo
   with one command; a benchmark you cannot re-run is a marketing claim.
2. **State the environment.** Hardware, container limits, and dataset size are reported with every
   result. "50k req/s" without a machine spec means nothing.
3. **Report p99, not averages.** Mean latency hides exactly the tail that users experience.
4. **Assert correctness under load.** Throughput with a broken invariant is not a passing result —
   every run ends with a verification query.
5. **Report failures.** If a target is missed, the number and the reason are published as-is. A
   portfolio of only green results is not credible.

---

## Environment (to be recorded per run)

| Item | Value |
|---|---|
| Host CPU / RAM | TBD |
| Docker resource limits per service | TBD |
| JVM flags | `-XX:+UseZGC -Xmx1g -Djdk.tracePinnedThreads=full` |
| Redis / Postgres / Kafka versions | 7.x / 16 / 3.7 KRaft |
| Load generator location | Same host (network latency excluded — stated explicitly) |

---

## Scenarios

### S1 — Location ingestion soak

| | |
|---|---|
| **Goal** | Sustain the write path at target volume |
| **Load** | 10,000 driver WebSockets, 1 Hz, 10 minutes, drivers moving on a simulated road grid |
| **Pass** | ≥ 9,500 effective updates/s; ingest p99 < 50 ms; zero connection drops; `location_frames_dropped_total` = 0 |

### S2 — Ride request burst

| | |
|---|---|
| **Goal** | Behavior at the request-rate ceiling |
| **Load** | Ramp 0 → 500 ride req/s over 2 min, hold 5 min, against a warm 10k-driver pool |
| **Pass** | API p99 < 120 ms; end-to-end match p99 < 500 ms; match success ≥ 95%; error rate < 0.1% |

### S3 — Contention stress (the headline test)

| | |
|---|---|
| **Goal** | Force the concurrency invariant to fail if it can |
| **Load** | 5,000 ride requests concentrated in a 1 km² area against only 200 available drivers |
| **Pass** | **Zero** drivers with more than one active trip; every request resolves to `ACCEPTED` or `UNMATCHED`; no unhandled exceptions; contention counter is non-zero (proving the race was actually exercised — a green result with zero contention proves nothing) |

### S4 — Idempotency under retry storm

| | |
|---|---|
| **Goal** | Duplicate suppression at concurrency |
| **Load** | 1,000 unique keys, each fired 5× simultaneously |
| **Pass** | Exactly 1,000 rides created; all responses for a key carry the same `rideId` |

### S5 — Chaos: dependency failure

| | |
|---|---|
| **Goal** | Verify degradation is graceful, not catastrophic |
| **Steps** | Under S2 load: (a) kill notification-service; (b) `docker pause` Redis for 10 s; (c) kill a matching-engine instance |
| **Pass** | (a) matching latency unaffected, consumer lag recovers to zero on restart with no lost events; (b) API returns `503` rather than hanging, full recovery within 5 s of resume; (c) partitions rebalance, no ride lost or double-assigned |

### S6 — Breaking point

| | |
|---|---|
| **Goal** | Find and report the actual limit |
| **Load** | Ramp ride requests until p99 > 1 s or errors > 1% |
| **Output** | The number, plus which resource saturated (CPU / Redis / connection pool / consumer lag) |

---

## Tooling

- **k6** for HTTP scenarios (S2, S4, S6) — `loadtest/scenarios/*.js`
- **Java simulator** for WebSocket load (S1, S3) — one virtual thread per driver, moving on a road
  grid with realistic speed and turn distributions
- **Prometheus** scraping every service at 5 s; **Grafana** dashboards provisioned as committed JSON
- **`docker pause` / `docker kill`** for chaos steps (S5)

Run:

```bash
docker compose -f ops/docker-compose.yml up -d
./loadtest/run.sh s2
```

---

## Post-run correctness gate

Every scenario ends with this query. A non-empty result fails the run regardless of latency numbers.

```sql
SELECT driver_id, count(*) AS active_trips
FROM trips
WHERE status IN ('OFFERED', 'ACCEPTED', 'IN_PROGRESS')
GROUP BY driver_id
HAVING count(*) > 1;
-- Expected: 0 rows
```

Plus: no records in any `*.dlt` topic, and every `ride.requested` event reconciles to exactly one
terminal state.

---

## Results

| Scenario | Target | Measured | Grafana |
|---|---|---|---|
| S1 Ingestion soak | 10k updates/s, p99 < 50 ms | TBD | TBD |
| S2 Request burst | 500 req/s, match p99 < 500 ms | TBD | TBD |
| S3 Contention | 0 double-assignments | TBD | TBD |
| S4 Idempotency | 0 duplicate rides | TBD | TBD |
| S5 Chaos | Graceful degradation | TBD | TBD |
| S6 Breaking point | — | TBD | TBD |
