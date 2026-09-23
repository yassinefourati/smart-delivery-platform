# syntax=docker/dockerfile:1
# Build context MUST be the repository root, e.g.:
#   docker build -f product-service/Dockerfile -t product-service .
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
COPY product-service/src product-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl product-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# A numeric UID, because under runAsNonRoot the kubelet can only verify a numeric USER --
# `USER spring` would stop every pod from starting. See docs/kubernetes.md.
RUN addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
WORKDIR /app

# Root-owned and read-only. The chown this replaced rewrote the jar into a second layer,
# so every published image carried two copies of it (96MB on order-service).
COPY --from=build /workspace/product-service/target/*.jar /app/app.jar

USER 1000:1000
EXPOSE 8082

# JVM flags. Why a heap percentage rather than -Xmx, why not the usual 75%, and why the
# `exec` is load-bearing (the JVM must be PID 1 or SIGTERM never reaches it, and graceful
# shutdown never runs) are in docs/kubernetes.md. JAVA_OPTS replaces this set;
# JAVA_OPTS_APPEND adds to it and wins on any flag it repeats, with no rebuild.
#
# This service specifically:
#   55% of 1Gi, and the only backend service with MaxDirectMemorySize set: Lettuce, the
#   Redis client spring-boot-starter-data-redis brings in, is Netty-based, so it has the
#   same unbounded-direct-memory shape as the gateway at a fraction of the traffic. The
#   1Gi limit rather than 768Mi is for the read-heavy path -- cached product payloads are
#   serialized and deserialized on the heap on every cache hit.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=55.0 -XX:MaxRAMPercentage=55.0 -XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS $JAVA_OPTS_APPEND -jar /app/app.jar"]
