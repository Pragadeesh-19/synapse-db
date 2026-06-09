# syntax=docker/dockerfile:1
# ── Stage 1: build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1000 --no-create-home --shell /usr/sbin/nologin synapse \
    && mkdir -p /data /config \
    && chown synapse:synapse /data /config

COPY --from=builder /build/target/synapse-db-1.0.0-SNAPSHOT.jar /app/synapse-db.jar

ENV SYNAPSE_DATA_DIR=/data
ENV SYNAPSE_CONFIG_DIR=/config

EXPOSE 8080

HEALTHCHECK --start-period=20s --interval=30s --timeout=5s --retries=3 \
    CMD curl -f http://localhost:8080/v3/api-docs || exit 1

USER synapse

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-XX:MaxRAMPercentage=75.0", "--add-opens", "jdk.unsupported/sun.misc=ALL-UNNAMED", "-jar", "/app/synapse-db.jar"]
