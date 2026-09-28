FROM gradle:8.14.4-jdk21-alpine AS build

WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle . .
USER gradle
RUN ./gradlew :apps:provider-simulator:installDist --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-alpine

RUN addgroup -S courtpulse && adduser -S -G courtpulse courtpulse
WORKDIR /app
COPY --from=build --chown=courtpulse:courtpulse \
    /workspace/apps/provider-simulator/build/install/provider-simulator/ /app/

USER courtpulse
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=12 \
    CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/bin/provider-simulator"]
