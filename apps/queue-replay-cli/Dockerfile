FROM gradle:8.14.4-jdk21-alpine AS build

WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle . .
USER gradle
RUN ./gradlew :apps:queue-replay-cli:bootJar --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-alpine

RUN addgroup -S courtpulse && adduser -S -G courtpulse courtpulse
WORKDIR /app
COPY --from=build --chown=courtpulse:courtpulse \
    /workspace/apps/queue-replay-cli/build/libs/queue-replay-cli-0.1.0-SNAPSHOT.jar \
    /app/courtpulse-delivery-worker.jar

USER courtpulse
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD find /tmp/courtpulse-delivery-worker.heartbeat -mmin -1 | grep -q heartbeat || exit 1
ENTRYPOINT ["java", "-jar", "/app/courtpulse-delivery-worker.jar", "--delivery-daemon"]
