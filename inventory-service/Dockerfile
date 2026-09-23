# syntax=docker/dockerfile:1
# Build context MUST be the repository root, e.g.:
#   docker build -f inventory-service/Dockerfile -t inventory-service .
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Leverage Docker layer caching: resolve dependencies before copying full sources
COPY pom.xml .
COPY platform-starter/pom.xml platform-starter/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY delivery-service/pom.xml delivery-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

COPY platform-starter/src platform-starter/src
COPY inventory-service/src inventory-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl inventory-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# A numeric UID, because under runAsNonRoot the kubelet can only verify a numeric USER --
# `USER spring` would stop every pod from starting. See docs/kubernetes.md.
RUN addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
WORKDIR /app

# Root-owned and read-only. The chown this replaced rewrote the jar into a second layer,
# so every published image carried two copies of it (96MB on order-service).
COPY --from=build /workspace/inventory-service/target/*.jar /app/app.jar

USER 1000:1000
EXPOSE 8083

# JVM flags. Why a heap percentage rather than -Xmx, why not the usual 75%, and why the
# `exec` is load-bearing (the JVM must be PID 1 or SIGTERM never reaches it, and graceful
# shutdown never runs) are in docs/kubernetes.md. JAVA_OPTS replaces this set;
# JAVA_OPTS_APPEND adds to it and wins on any flag it repeats, with no rebuild.
#
# This service specifically:
#   60% of a 768Mi limit. Short transactions over small entity graphs plus the outbox
#   poller; nothing here holds much on the heap for long.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=60.0 -XX:MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS $JAVA_OPTS_APPEND -jar /app/app.jar"]
