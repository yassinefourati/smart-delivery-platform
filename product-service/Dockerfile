# syntax=docker/dockerfile:1
# Build context MUST be the repository root, e.g.:
#   docker build -f product-service/Dockerfile -t product-service .
FROM maven:3.9-eclipse-temurin-21-alpine AS build
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

COPY product-service/src product-service/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl product-service -am -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /workspace/product-service/target/*.jar app.jar
RUN chown spring:spring app.jar
USER spring:spring
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
