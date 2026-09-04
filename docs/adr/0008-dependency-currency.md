# ADR-0008: Track supported dependency versions, and how the Spring Boot 4 upgrade went

**Status:** Accepted · **Date:** 2026-09-04

## Context

The project was started on **Spring Boot 3.3.5**. OSS support for the 3.3 line ended on
**2025-06-30** — over a year before the first commit here. The version was chosen from memory as
"a recent 3.x" and pinned because it was known to resolve, not because it was current. Nobody
checked the support calendar until the Spring tooling started emitting end-of-support warnings.

That is a worse defect than it first appears in a repository whose entire pitch is
"production-shaped":

- **No security patches.** After OSS end-of-life, CVEs in that line are fixed only for commercial
  Enterprise subscribers. An unsupported framework is a standing vulnerability, not a stylistic
  preference.
- **It undermines everything else.** A reviewer who reads ADR-0004's careful analysis of Redlock's
  failure modes and then finds a framework a year past end-of-life in `pom.xml` will reasonably
  conclude the rigor is selective.

## Decision

1. **Upgrade to Spring Boot 4.1.1**, the current GA release. Spring's guidance for anyone on 3.x is
   to move to 4.0.x or 4.1.x to keep receiving OSS support.
2. **Verify version currency at the point of pinning**, not from memory. Dependency versions are the
   category of fact most likely to be stale; the check costs one lookup.
3. **Track Boot's tested versions for the libraries it knows about.** `testcontainers.version` mirrors
   the property in `spring-boot-dependencies` rather than being chosen independently — pinning it
   separately is how you end up debugging a combination nobody ships.
4. **Java stays at 21 for now.** Boot 4 keeps Java 17 as its baseline, so 21 is fully supported. Java
   25 is a separate, worthwhile change discussed below.

## What the upgrade actually required

Four changes, all mechanical once found:

| Change | Detail |
|---|---|
| `spring-boot.version` | `3.3.5` → `4.1.1` |
| Jackson 2 → 3 | Boot 4 ships Jackson 3.1.5. The package moved from `com.fasterxml.jackson` to `tools.jackson`; two imports changed |
| Testcontainers 1.x → 2.0.5 | The JUnit 5 artifact was renamed `junit-jupiter` → `testcontainers-junit-jupiter` |
| Testcontainers BOM | Boot 4 defines `testcontainers.version` but no longer imports the BOM, so the import must be explicit |

The Jackson change is the one worth noting: it is a **runtime** concern that compiles cleanly either
way once imports are fixed, so the compiler is not the safety net. Deserialization of every driver
GPS frame goes through it, and correctness was confirmed by driving real WebSocket connections and
checking that frames still land in the geospatial index — not by the build passing.

**A rule that silently stopped working:** `ArchitectureTest` forbids the domain module from depending
on `com.fasterxml.jackson..`. Under Jackson 3 that package no longer exists, so the rule would have
kept passing while the hole it guards — a Jackson annotation creeping into a domain record — was wide
open. The rule now bans both package roots. This is the characteristic failure of guard rails: they
do not announce that they have stopped guarding anything.

## Consequences

**Positive**

- Back on a supported line receiving security patches.
- Newer Spring Framework 7, Tomcat 11, Hibernate 7.1 underneath.
- One fewer contradiction between what the README claims and what the build does.

**Negative**

- Jackson 3 is a genuine ecosystem split. Any future library expecting Jackson 2 needs the deprecated
  compatibility layer or replacing.
- Boot 4 removed public members from auto-configuration classes and renamed several properties
  (`management.tracing.enabled` → `management.tracing.export.enabled` among them). None affected this
  codebase today, but they will constrain later phases.
- Testcontainers 2.x is a major version whose full API surface has not been exercised here — only the
  parts `RedisDriverLocationIndexIT` uses.

## Java 25: deferred, not rejected

Boot 4 gives first-class support for **Java 25** while keeping Java 17 as the floor. The reason to
move is specific rather than cosmetic: **JDK 24 shipped JEP 491, removing the virtual-thread pinning
hazard on `synchronized` blocks.** [ADR-0006](0006-java21-virtual-threads.md) currently lists that as
a live constraint requiring `ReentrantLock` discipline and `-Djdk.tracePinnedThreads` in the
load-test profile.

Upgrading would let that ADR **delete a caveat rather than manage one**. It is deferred only because
it requires rebuilding the dev container onto a new JDK base image, which is a separate change with
its own verification.

## Verification

`mvn clean verify` — 82 tests green, including the 8 Testcontainers-backed Redis integration tests.
Beyond the suite, the location service was run against live Redis and driven with 20 concurrent
WebSocket connections: 840 frames accepted, 800 correctly throttled, 0 rejected, 20 drivers indexed,
and `GEOSEARCH` still ranking by distance.
