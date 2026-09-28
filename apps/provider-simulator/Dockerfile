FROM gradle:8.14.4-jdk21-alpine AS build

WORKDIR /workspace
RUN chown gradle:gradle /workspace
COPY --chown=gradle:gradle . .
USER gradle
# The pinned build image already contains Gradle 8.14.4 (the wrapper's version), so builds do not
# download a distribution; the cache mount keeps dependencies between image builds.
RUN --mount=type=cache,id=courtpulse-gradle,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    gradle :apps:provider-simulator:installDist --no-daemon --console=plain

FROM eclipse-temurin:21.0.12_8-jre-alpine

# Alpine security fixes often land before the next base-image release; take them at build time.
RUN apk upgrade --no-cache && addgroup -S courtpulse && adduser -S -G courtpulse courtpulse
WORKDIR /app
COPY --from=build --chown=courtpulse:courtpulse \
    /workspace/apps/provider-simulator/build/install/provider-simulator/ /app/

USER courtpulse
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=12 \
    CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["/app/bin/provider-simulator"]
