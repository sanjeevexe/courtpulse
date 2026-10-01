# syntax=docker/dockerfile:1.7
FROM gradle:9.8.0-jdk21-alpine AS build

WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle . .
USER gradle
# The pinned build image already contains Gradle 8.14.4 (the wrapper's version), so builds do not
# download a distribution; the cache mount keeps dependencies between image builds.
RUN --mount=type=cache,id=courtpulse-gradle,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    gradle :apps:queue-replay-cli:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21.0.12_8-jre-alpine

# Alpine security fixes often land before the next base-image release; take them at build time.
RUN apk upgrade --no-cache && addgroup -S courtpulse && adduser -S -G courtpulse courtpulse
WORKDIR /app
# The pinned agent is inert unless JAVA_TOOL_OPTIONS adds -javaagent (observability profile or cloud tracing).
ADD --checksum=sha256:bbf83c151b6400709e2f225bdd07a04f839d9d13b8b93464241333fd25d3e3ba \
    https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.31.1/opentelemetry-javaagent-2.31.1.jar \
    /opt/otel/opentelemetry-javaagent.jar
RUN chmod 444 /opt/otel/opentelemetry-javaagent.jar
COPY --from=build --chown=courtpulse:courtpulse \
    /workspace/apps/queue-replay-cli/build/libs/queue-replay-cli-0.1.0-SNAPSHOT.jar \
    /app/courtpulse-worker.jar

USER courtpulse
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
# Every daemon mode writes /tmp/courtpulse-<mode>-worker.heartbeat after each healthy pass.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD find /tmp -maxdepth 1 -name 'courtpulse-*-worker.heartbeat' -mmin -1 | grep -q heartbeat || exit 1
ENTRYPOINT ["java", "-jar", "/app/courtpulse-worker.jar"]
CMD ["--delivery-daemon"]
