# Development Environment

The host runs **only VS Code and Podman**. JDK 25, Maven, Postgres, Redis, and Kafka all live in
containers and leave no trace on the machine. Rationale in [ADR-0007](adr/0007-containerized-dev-environment.md).

```
[ Windows host ]
      │  VS Code + Podman CLI only
      ▼
[ podman machine — a WSL 2 Linux VM ]
      │
      ├── workspace container   JDK 25, Maven, Git   <- VS Code attaches here
      ├── postgres:16-alpine    :5432
      ├── redis:7-alpine        :6379
      ├── kafka (KRaft)         :9092 internal / :29092 from Windows
      └── prometheus + grafana  (profile: observability)
```

All of these are **siblings on one network**, not nested inside each other — see ADR-0007 for why
Docker-in-Docker was rejected.

---

## One-time host setup

Verified working on 2026-09-03 with Podman 6.0.2 (rootless, WSL backend, 4 CPU / 8 GiB) and
Docker Compose v5.5.1: Postgres 16.15, Redis 7 (`GEOSEARCH` confirmed), and Kafka 3.8.1 all healthy,
with the full topic topology created and a produce/consume round trip passing.

### 1. WSL 2

Podman needs a Linux VM on Windows. In an **Administrator** PowerShell:

```powershell
wsl --install --no-distribution
```

`--no-distribution` installs the WSL platform without an Ubuntu install you would never use —
`podman machine` creates its own minimal distro. **Reboot afterwards**, then confirm:

```powershell
wsl --status
```

### 2. Podman engine

Podman Desktop is the GUI; it does not include the engine. Install the CLI:

```powershell
winget install --exact --id RedHat.Podman --source winget
```

`--source winget` matters: without it, winget stops to ask you to accept `msstore` source
agreements it does not need for this package.

Open a **new** terminal (PATH changes need a fresh shell), then create and start the VM:

```powershell
podman machine init --cpus 4 --memory 8192 --disk-size 60
podman machine start
podman info
```

Kafka plus Postgres plus a JVM in one VM is memory-hungry; 8 GB is a realistic floor, 4 GB will
cause the broker to be OOM-killed under load testing.

### 3. A compose implementation

`podman compose` is only a shim — it delegates to a real compose binary and fails with
"looking up compose provider failed" if none is installed.

```powershell
winget install --exact --id Docker.DockerCompose --source winget
```

Compose v2 is the right choice over `podman-compose`: this project's compose file relies on
`depends_on: condition: service_healthy` and `profiles`, both of which Compose v2 supports fully
and the third-party `podman-compose` historically does not. Once installed, `podman compose`
auto-detects it and points `DOCKER_HOST` at the machine socket; invoking `docker-compose` directly
will not find Podman.

### 4. Point VS Code at Podman

In VS Code settings JSON (`Ctrl+Shift+P` → *Preferences: Open User Settings (JSON)*):

```json
{
  "dev.containers.dockerPath": "podman",
  "dev.containers.dockerComposePath": "podman-compose"
}
```

If `podman-compose` is not present, install it (`pip install podman-compose`) or use Podman's
built-in `podman compose` shim by setting the value to `podman compose`.

---

## Daily use

Open the project folder in VS Code → *Reopen in Container* when prompted (or `Ctrl+Shift+P` →
**Dev Containers: Reopen in Container**).

First build pulls images and installs Maven — several minutes. Subsequent opens are seconds.

On success, `post-create.sh` prints the toolchain versions and TCP-checks all three services. If it
reports `FAIL`, the containers are probably still starting; re-run it:

```bash
bash .devcontainer/post-create.sh
```

### Without VS Code

The infrastructure runs standalone:

```bash
podman compose -f ops/docker-compose.yml up -d
```

Add observability (off by default to keep startup light):

```bash
podman compose -f ops/docker-compose.yml --profile observability up -d
```

### The full system

Services live behind the `app` profile so routine dev-container work does not rebuild six images.
Jars first, then images:

```bash
./mvnw package -DskipTests
podman compose -f ops/docker-compose.yml --profile app --profile observability up -d --build
```

| | |
|---|---|
| Grafana | `localhost:3000` — anonymous admin, "Ride Matching" folder |
| Prometheus | `localhost:9090` — check Status → Targets if a panel is empty |
| Services | `localhost:8080`–`8085` |

Rebuild one service after a code change:

```bash
./mvnw package -DskipTests -pl services/dispatch-api -am
podman compose -f ops/docker-compose.yml --profile app up -d --build dispatch-api
```

### Kafka topics

The broker has `auto.create.topics.enable=false`, so a typo in a topic name fails loudly instead of
silently creating a topic with default partitioning. Create the topology from
[ADR-0003](adr/0003-kafka-topology-and-outbox.md):

```bash
podman exec -i ride-matching-engine-kafka-1 bash -s < ops/kafka/create-topics.sh
```

The script is idempotent (`--if-not-exists`), so re-running it after adding a topic is safe.

**Run that from Git Bash, not PowerShell.** Piping a file into a container through PowerShell 5.1
prepends a UTF-8 BOM and rewrites newlines as CRLF, which makes the shebang line fail with
`#!/usr/bin/env: No such file or directory` and leaves stray `$'\r'` errors. Inside the dev
container, just run `bash ops/kafka/create-topics.sh`.

Also note that Git Bash rewrites container-absolute paths: `podman exec ... /opt/kafka/bin/...`
becomes `C:/Program Files/Git/opt/kafka/...`. Prefix commands with `MSYS_NO_PATHCONV=1` when passing
absolute paths into a container.

---

## Connection reference

| Service | From inside containers | From Windows |
|---|---|---|
| PostgreSQL | `postgres:5432` | `localhost:5432` |
| Redis | `redis:6379` | `localhost:6379` |
| Kafka | `kafka:9092` | `localhost:29092` |
| Prometheus | `prometheus:9090` | `localhost:9090` |
| Grafana | — | `localhost:3000` (anonymous admin) |

Credentials are `ride` / `ride` / database `ride`. They are hardcoded deliberately: this stack is
local-only and disposable, and inventing secret management for it would be theatre.

**The two Kafka ports are not interchangeable.** A client that connects on the wrong listener gets a
metadata response advertising a hostname it cannot resolve, then hangs until it times out — a
confusing failure with a boring cause.

---

## Troubleshooting

**`podman machine start` fails, or WSL errors**
Confirm virtualization is enabled in BIOS/UEFI and that `wsl --status` reports version 2. Hyper-V
and WSL 2 can conflict on some machines; WSL 2 is the supported backend here.

**`EACCES` / permission denied writing to `/workspace`**
Rootless Podman maps container root to your host user, but the dev container runs as `vscode`
(uid 1000). Uncomment `userns_mode: "keep-id"` in
[.devcontainer/docker-compose.dev.yml](../.devcontainer/docker-compose.dev.yml) and rebuild. This is
Podman-only; Docker rejects that value.

**Bind mount is empty or unreadable on a SELinux host**
The `:z` suffix on the volume mounts handles relabelling. It is already set; if you add mounts,
match it.

**Kafka container restarts in a loop**
Almost always memory. Check `podman machine inspect` for the VM's allocation, and confirm the
replication-factor variables are all `1` — a single broker cannot satisfy a replication factor of 3,
and the broker exits rather than degrading.

**VS Code can't find the container engine**
`dev.containers.dockerPath` must be set (step 3). Restart VS Code fully after changing it; the
extension reads the setting at activation.

**Integration tests hang for 60s then fail with "Timed out waiting for container port to open"**
Containers a test starts are siblings of the dev container, so their published ports appear on the
Podman VM's host interface rather than on the dev container's loopback — which is where
Testcontainers looks by default. `TESTCONTAINERS_HOST_OVERRIDE=host.containers.internal` in
[docker-compose.dev.yml](../.devcontainer/docker-compose.dev.yml) fixes it. If you see this, the
container predates that setting: rebuild it.

**"Ryuk has been disabled" in test output**
Expected. Ryuk is the reaper container Testcontainers uses to clean up after a crashed JVM, and it
needs privileges rootless Podman will not grant. Containers are removed by the JVM shutdown hook
instead. A hard-killed test run can leave stray containers; `podman ps -a` will show them.

**Everything is broken and you want a clean slate**

```bash
podman compose -f ops/docker-compose.yml down -v
podman system prune -a
```

`down -v` deletes the named volumes, so Postgres data and Kafka logs go with it. That is usually what
you want in dev, and never what you want anywhere else.
