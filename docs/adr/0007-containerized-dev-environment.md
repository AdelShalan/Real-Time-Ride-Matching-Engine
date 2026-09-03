# ADR-0007: Containerized development environment on Podman

**Status:** Accepted · **Date:** 2026-09-03

## Context

The stack needs JDK 21, Maven, Postgres, Redis, and Kafka. Installing those on the host means version
drift between the developer's machine and CI, an uninstall trail across the registry and PATH, and a
project that cannot be reproduced by a reviewer who clones it.

The requirement is that the host runs **only** VS Code and a container engine; everything else lives
inside containers and disappears when the project is deleted.

## Decision

1. **Podman as the engine**, not Docker Desktop. Rootless, daemonless, no licence terms for
   commercial use, and no privileged background service on the host.
2. **A Dev Container** (`.devcontainer/devcontainer.json`) holding JDK 21, Maven, and the Java
   extensions. VS Code attaches to it; the host never sees a JDK.
3. **Compose siblings, not Docker-in-Docker.** The dev container and the infrastructure containers
   are peers on one compose network (`ride-net`), declared across `ops/docker-compose.yml` and
   `.devcontainer/docker-compose.dev.yml`.

## Why siblings instead of `docker-in-docker`

The obvious setup — a plain dev container plus the `docker-in-docker` feature, starting Postgres and
friends *inside* the sandbox — was rejected:

- **It fights rootless Podman.** DinD needs a privileged container running a nested daemon. Under
  rootless Podman that means nested user namespaces and `--privileged`, which is exactly the host
  intrusion this setup exists to avoid, and is a well-known source of fragile storage-driver and
  cgroup failures.
- **It rebuilds the world on every container rebuild.** Nested containers and their volumes live
  inside the outer container. Rebuilding the dev container discards the Kafka log dirs and the
  Postgres data directory.
- **The compose file has to exist anyway.** `ops/docker-compose.yml` is needed for CI, for load
  testing, and for the one-command startup the README promises. Reusing that same file as the dev
  container's base means the dev environment and CI run byte-identical service definitions, rather
  than two definitions that drift.
- **Isolation is unchanged.** The containers are siblings under the same rootless Podman machine.
  Nothing lands on the host either way — the only difference is which process supervises them.

Docker-in-Docker earns its cost when the workload itself builds or runs containers (a CI runner, a
Testcontainers suite). **Testcontainers is in this project's test strategy**, and it is the one thing
that would need a container endpoint from inside the dev container. That is solved by mounting the
Podman socket into the dev container rather than by nesting a second daemon — deferred to Phase 2,
when the first integration test is written.

## Consequences

**Positive**

- The host keeps only VS Code and Podman. Deleting the project folder and running
  `podman system prune -a` removes every trace.
- The same compose file drives the dev container, local runs, and CI.
- Service data survives dev-container rebuilds, because the volumes belong to the outer engine.
- Contributors get an identical toolchain with no README full of install instructions.

**Negative**

- Requires WSL 2 on Windows: Podman runs a Linux VM (`podman machine`). That is a real host
  prerequisite, and the one thing this approach cannot containerize away.
- Podman's Docker-API compatibility is good but not perfect; VS Code needs
  `dev.containers.dockerPath` pointed at `podman`, and rootless bind mounts sometimes need
  `userns_mode: keep-id`. Both are documented in [DEV_ENVIRONMENT.md](../DEV_ENVIRONMENT.md).
- Bind-mounted source crosses the Windows → VM filesystem boundary, which is slower than native I/O.
  Mitigated by keeping the Maven repository in a named volume rather than on the bind mount.

## Alternatives considered

- **Docker Desktop** — smoothest Dev Containers experience by a distance, but installs a privileged
  background service and carries licence terms for commercial use. Rejected on the isolation goal.
- **Host-installed JDK + Maven, containers only for infra** — simpler, and what most people do.
  Rejected because it puts a toolchain on the host and reintroduces version drift with CI.
- **Full VM (Vagrant/Multipass)** — stronger isolation, far heavier, and loses the editor integration
  that makes Dev Containers worth using.
