#!/usr/bin/env bash
# Container healthcheck. Speaks HTTP over bash's /dev/tcp so the image needs no curl or wget:
# installing a package for one HTTP call adds a layer and makes the image build depend on an
# apt mirror being healthy, which has already failed here once.
#
# Asserts the response BODY says UP rather than merely that the port accepts connections — a
# service whose database connection is dead still answers on 8080 and reports DOWN.
set -euo pipefail

PORT="${HEALTHCHECK_PORT:-8080}"

exec 3<>"/dev/tcp/127.0.0.1/${PORT}"
printf 'GET /actuator/health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
grep -q '"status":"UP"' <&3
