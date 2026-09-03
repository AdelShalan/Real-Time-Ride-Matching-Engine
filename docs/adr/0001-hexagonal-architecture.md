# ADR-0001: Hexagonal architecture in a multi-module Maven build

**Status:** Accepted · **Date:** 2026-09-03

## Context

The system has six deployable services sharing a domain model, and it must be testable without
Redis, Kafka, or Postgres running. The two common failure modes for a project like this are (a) a
single Spring package where domain logic is tangled with `@Entity` and `@KafkaListener` annotations,
and (b) premature microservice sprawl where each service has its own repo, build, and drift.

## Decision

A **multi-module Maven build** containing independently deployable Spring Boot services, with each
service internally structured as **ports and adapters**:

```
domain/        entities, value objects, invariants     — zero framework imports
application/   use cases + port interfaces             — orchestration only
adapters/in/   REST controllers, WS handlers, Kafka consumers
adapters/out/  Redis, Postgres, Kafka producer implementations
```

The dependency rule points inward: `adapters → application → domain`. The domain module has no
Spring, no Jackson, no JPA on its classpath — enforced in CI by ArchUnit, not by discipline.

## Consequences

**Positive**

- `TripStateMachine`, driver scoring, and claim-decision logic are plain-Java unit tests that run in
  milliseconds. The slow Testcontainers suite only covers adapters.
- Swapping Redis GEO for PostGIS is an adapter change behind `DriverLocationIndex`, not a rewrite —
  which also makes it possible to *benchmark* both implementations against the same port.
- One `mvn verify` builds and tests everything; no cross-repo version coordination.

**Negative**

- More packages and interfaces than a flat layout; genuine overhead on a small codebase, accepted
  because demonstrating boundary discipline is part of the point of this project.
- Shared `libs/domain` creates coupling between services. Mitigated by keeping it strictly to pure
  model types with no infrastructure concerns, and by versioning event schemas separately in
  `libs/events`.

## Alternatives considered

- **Single Spring Boot monolith.** Simplest to build, but the Kafka decoupling story becomes a
  fiction — you cannot demonstrate that killing the notification consumer leaves matching unaffected
  if they share a process.
- **Separate repositories per service.** Realistic at company scale, hostile to a reviewer who wants
  to read the system in one place.
