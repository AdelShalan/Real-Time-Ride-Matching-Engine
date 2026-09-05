# One image definition for every service; the service name is a build argument.
#
# This packages a jar that Maven has already built rather than building inside the image.
# A multi-stage build would be self-contained, but it recompiles the whole reactor once per
# service — six times — and the jars are a build artifact, not a source input. Building once
# and packaging six times is both faster and the way CI actually works.
#
#   ./mvnw package -DskipTests
#   podman compose -f ops/docker-compose.yml --profile app up -d --build

FROM eclipse-temurin:25-jre-noble

ARG SERVICE
ENV SERVICE=${SERVICE}

# No package installs on purpose. Reaching out to apt makes the image build depend on a
# mirror being healthy — it already failed once here with a stale package index — and adds a
# layer for a single HTTP call. The healthcheck in docker-compose.yml uses bash's /dev/tcp
# instead, which is built in.

# Non-root: nothing here needs privileges, and a container that runs as root is one
# container-escape bug away from being a host problem.
RUN useradd --system --create-home --uid 10001 app
USER app
WORKDIR /app

COPY --chown=app:app services/${SERVICE}/target/${SERVICE}-*-exec.jar /app/app.jar
COPY --chown=app:app --chmod=755 ops/healthcheck.sh /app/healthcheck.sh

# A default, not a decision: docker-compose.yml overrides JAVA_OPTS per service, choosing ZGC
# for the three with p99 SLOs and SerialGC for the three that only log and count.
#
# MaxRAMPercentage is container-aware — the JVM reads its cgroup limit — but that only helps
# if a limit exists. With none, the JVM sees the whole VM and concludes it may grow to several
# GB, which is how six services exhausted the machine. Every service in compose has a
# mem_limit for exactly this reason.
#
# 50, not 70: the limit covers heap PLUS metaspace, code cache, GC metadata, thread stacks and
# direct buffers. Non-heap for a small Spring Boot service is ~150-200MB, so a 70% heap inside
# a 320MB limit would be OOM-killed rather than merely tight.
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=50"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
