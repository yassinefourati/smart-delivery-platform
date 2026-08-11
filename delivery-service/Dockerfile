# syntax=docker/dockerfile:1
# Build context MUST be the repository root, e.g.:
#   docker build -f delivery-service/Dockerfile -t delivery-service .
FROM maven:3-eclipse-temurin-26-alpine AS build
WORKDIR /workspace

# Leverage Docker layer caching: resolve dependencies before copying full sources
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY delivery-service/pom.xml delivery-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml

COPY delivery-service/src delivery-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl delivery-service -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/delivery-service/target/*.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
