#!/usr/bin/env bash
# Creates the topic topology from ADR-0003. Idempotent: re-running is a no-op.
#
# Auto-topic-creation is disabled on the broker deliberately — a typo in a topic name
# should fail loudly, not silently create a new topic with default partitioning.
#
# Run inside the dev container (or any host with the Kafka CLI on PATH):
#     bash ops/kafka/create-topics.sh
#
# Or from a Windows host, piped into the broker container:
#     podman exec -i ride-matching-engine-kafka-1 bash -s < ops/kafka/create-topics.sh
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"
KAFKA_BIN="${KAFKA_BIN:-/opt/kafka/bin}"

# name : partitions : retention_ms : extra configs
# Partition counts set the ceiling on consumer parallelism; retention differs sharply
# between disposable telemetry and auditable ride events.
TOPICS=(
  "driver.location.v1:12:3600000:compression.type=lz4"
  "ride.requested.v1:12:604800000:"
  "ride.matched.v1:12:604800000:"
  "ride.unmatched.v1:6:604800000:"
  "ride.completed.v1:12:604800000:"
  "ride.cancelled.v1:6:604800000:"
  "driver.location.v1.dlt:3:2592000000:"
  "ride.requested.v1.dlt:3:2592000000:"
  "ride.matched.v1.dlt:3:2592000000:"
)

echo "==> Broker: ${BOOTSTRAP}"

for entry in "${TOPICS[@]}"; do
  IFS=':' read -r name partitions retention extra <<< "$entry"

  args=(
    --bootstrap-server "$BOOTSTRAP"
    --create
    --if-not-exists
    --topic "$name"
    --partitions "$partitions"
    # Single broker in dev; production would raise this to 3 with min.insync.replicas=2.
    --replication-factor 1
    --config "retention.ms=${retention}"
  )
  [ -n "$extra" ] && args+=(--config "$extra")

  "${KAFKA_BIN}/kafka-topics.sh" "${args[@]}" >/dev/null
  printf "    %-28s partitions=%-3s retention=%sh\n" "$name" "$partitions" "$((retention / 3600000))"
done

echo
echo "==> Topics on broker:"
"${KAFKA_BIN}/kafka-topics.sh" --bootstrap-server "$BOOTSTRAP" --list | sed 's/^/    /'
