# Stage 1: Build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Dependencies first: this layer is cached until the build files change.
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon -q > /dev/null

COPY src ./src
# Tests run in CI / locally (they need Docker for Testcontainers), not inside the image build.
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user. The media directory must exist and belong to it *before* the volume is mounted,
# otherwise Docker creates the volume root-owned and uploads fail with "permission denied".
RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && mkdir -p /app/data/media \
    && chown -R appuser:appgroup /app/data

COPY --from=builder --chown=appuser:appgroup /app/build/libs/*.jar app.jar

USER appuser

# JVM options tuned for a 2GB VPS (container memory limit drives the heap size)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health/liveness > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
