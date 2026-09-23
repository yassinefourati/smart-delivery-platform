# syntax=docker/dockerfile:1
# Build context MUST be the repository root, e.g.:
#   docker build -f notification-service/Dockerfile -t notification-service .
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

COPY notification-service/src notification-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl notification-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
# A numeric UID, because under runAsNonRoot the kubelet can only verify a numeric USER --
# `USER spring` would stop every pod from starting. See docs/kubernetes.md.
RUN addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
WORKDIR /app

# Root-owned and read-only. The chown this replaced rewrote the jar into a second layer,
# so every published image carried two copies of it (96MB on order-service).
COPY --from=build /workspace/notification-service/target/*.jar /app/app.jar

USER 1000:1000
EXPOSE 8087

# JVM flags. Why a heap percentage rather than -Xmx, why not the usual 75%, and why the
# `exec` is load-bearing (the JVM must be PID 1 or SIGTERM never reaches it, and graceful
# shutdown never runs) are in docs/kubernetes.md. JAVA_OPTS replaces this set;
# JAVA_OPTS_APPEND adds to it and wins on any flag it repeats, with no rebuild.
#
# This service specifically:
#   The one service sized differently on every axis, because it is the one service with
#   no database: no JPA, no Hibernate, no Flyway, no datasource, no connection pool. Its
#   metaspace floor is roughly a third lower than every other service's, which is why
#   512Mi is enough and why the cap is 192m rather than 256m.
#
#   SerialGC is chosen here, not inherited. Java 21 would pick it anyway for a pod this
#   size, and that is precisely the problem: written down, it stays SerialGC when
#   somebody gives the pod a second CPU, instead of silently becoming G1 with different
#   pause behaviour and a larger footprint. For an asynchronous consumer whose entire
#   job is deserialize-then-log, a 30ms stop-the-world pause is invisible and one GC
#   thread is cheaper than G1's region bookkeeping.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=55.0 -XX:MaxRAMPercentage=55.0 -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS $JAVA_OPTS_APPEND -jar /app/app.jar"]
