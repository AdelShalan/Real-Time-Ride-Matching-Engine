#!/usr/bin/env bash
# Runs once after the dev container is created. Verifies the toolchain and that every
# infrastructure dependency is actually reachable, so a broken environment fails here
# with a clear message rather than later inside a confusing build error.
set -euo pipefail

echo "==> Toolchain"
java -version 2>&1 | sed 's/^/    /'
mvn -version 2>&1 | head -1 | sed 's/^/    /'
git --version | sed 's/^/    /'

echo
echo "==> Dependency reachability"

check() {
  local name="$1" host="$2" port="$3"
  if timeout 5 bash -c "cat < /dev/null > /dev/tcp/${host}/${port}" 2>/dev/null; then
    echo "    OK    ${name} (${host}:${port})"
  else
    echo "    FAIL  ${name} (${host}:${port}) unreachable"
    return 1
  fi
}

failed=0
check "PostgreSQL" postgres 5432 || failed=1
check "Redis"      redis    6379 || failed=1
check "Kafka"      kafka    9092 || failed=1

echo
if [ "$failed" -ne 0 ]; then
  echo "One or more dependencies are unreachable. The containers may still be starting;"
  echo "re-run with:  bash .devcontainer/post-create.sh"
  exit 1
fi

echo "==> Environment ready."
echo "    Postgres  jdbc:postgresql://postgres:5432/ride  (user/pass: ride/ride)"
echo "    Redis     redis:6379"
echo "    Kafka     kafka:9092        (from Windows: localhost:29092)"
echo
echo "    Observability is not running by default. To start it:"
echo "      podman compose -f ops/docker-compose.yml --profile observability up -d"
