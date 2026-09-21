FROM gradle:8.14.4-jdk21-alpine AS build

WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle . .
USER gradle
RUN ./gradlew :apps:api:bootJar --no-daemon

FROM eclipse-temurin:21.0.8_9-jre-alpine

RUN addgroup -S courtpulse && adduser -S -G courtpulse courtpulse
WORKDIR /app
COPY --from=build --chown=courtpulse:courtpulse \
    /workspace/apps/api/build/libs/api-0.1.0-SNAPSHOT.jar /app/courtpulse-api.jar

USER courtpulse
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 \
    CMD wget -q --spider http://127.0.0.1:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/courtpulse-api.jar"]
