# ADR-0006: Java 21 virtual threads instead of a reactive stack

**Status:** Accepted · **Date:** 2026-09-03

## Context

The Location Service must hold ~10,000 concurrent WebSocket connections, and the matching path is a
chain of blocking I/O calls (Redis → Postgres → Kafka). Classically this forces a choice: one
platform thread per connection (10k × ~1 MB of stack, and a scheduler under strain), or a reactive
stack (Project Reactor / WebFlux) that never blocks but rewrites all business logic as operator
chains.

## Decision

**Java 21 with virtual threads**, on Spring Boot MVC with
`spring.threads.virtual.enabled=true`; the driver simulator also runs one virtual thread per
simulated driver.

## Rationale

A virtual thread starts at roughly 1 KB of heap and its stack grows on demand; when it blocks on
I/O, the JVM unmounts it from its carrier thread and the carrier runs something else. The scalability
profile is the reactive one — a small pool of OS threads multiplexing many logical tasks — but the
code is ordinary sequential Java:

```java
// Reads top to bottom. Stack traces are complete. A debugger steps through it.
var candidates = geoIndex.search(pickup, radiusKm);      // blocks
var claim      = claimService.tryClaim(candidate.id());  // blocks
var trip       = tripRepository.assign(rideId, claim);   // blocks
eventPublisher.publish(new RideMatched(trip));           // blocks
```

The reactive equivalent of that block is a `flatMap` chain whose stack traces are near-useless, whose
exception handling is a separate vocabulary, and where one accidental blocking call inside an
operator quietly stalls an event-loop thread and degrades the whole service. For a codebase whose
purpose is to be *read and understood by a reviewer*, that cost is not worth paying for equivalent
throughput.

Virtual threads also make the simulator honest: 10,000 simulated drivers each in their own thread,
each with a straightforward `while (running) { move(); send(); sleep(1s); }` loop, is a faithful model
of 10,000 independent clients. Simulating that on an event loop would mean the load generator itself
becomes a nontrivial concurrency problem.

> **Update (2026-09-04):** this ADR's decision is unchanged, but the runtime moved from Java 21 to
> Java 25 LTS, which resolves the pinning constraint below. See
> [ADR-0008](0008-dependency-currency.md) for the measurement. The original text is kept as written.

## Known constraints, and how they are handled

- **Pinning.** A virtual thread blocking inside a `synchronized` block pins its carrier thread. All
  application locking uses `ReentrantLock`, and `-Djdk.tracePinnedThreads=full` runs in the load-test
  profile to catch regressions. (JDK 24+ removes most pinning; the discipline stays regardless.)
- **Unbounded concurrency.** Virtual threads are cheap, which makes it easy to overwhelm a downstream
  system. Every outbound dependency sits behind an explicit `Semaphore` or bounded pool — Redis and
  Postgres connection pools are the real limit and are sized deliberately.
- **ThreadLocal cost.** Per-thread caches are pointless when threads are per-request. Trace context
  propagates via Micrometer's context propagation rather than raw `ThreadLocal` accumulation.

## Consequences

**Positive**

- 10k WebSocket connections without a reactive rewrite; measured in the load test rather than assumed.
- Full, meaningful stack traces — including across the Kafka consumer boundary.
- Testcontainers integration tests are plain blocking code, with no `StepVerifier` scaffolding.

**Negative**

- Requires Java 21+; no backport path to Java 17 LTS.
- Virtual threads help with *blocking* concurrency, not CPU-bound work. The candidate-scoring loop is
  CPU-bound, so it stays on a bounded platform-thread pool.
- Fewer battle-tested production references than WebFlux. Mitigated by keeping the I/O layer behind
  ports ([ADR-0001](0001-hexagonal-architecture.md)) so a reactive adapter remains possible.

## Alternatives considered

- **WebFlux / Reactor** — proven at this scale, and the right answer if the team already thinks in
  reactive. Rejected for readability and debuggability, which matter more here.
- **Platform threads with a large pool** — ~10 GB of stack for 10k connections. Not viable.
- **Netty directly** — maximum control, far more code, and none of it is the interesting part of this
  project.
