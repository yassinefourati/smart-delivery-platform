# Architecture

## Overview

The Smart Delivery Platform is a set of independently deployable Spring Boot services,
each owning its own PostgreSQL database, fronted by a single API Gateway. Services
communicate synchronously over REST for request/response needs (e.g. the gateway
routing a client call) and asynchronously over Kafka for business events that other
services need to react to (e.g. an order being created triggers inventory reservation).

```mermaid
flowchart TB
    Client([Client])

    subgraph Edge
        Gateway[API Gateway]
    end

    subgraph Services
        User[User Service]
        Product[Product Service]
        Inventory[Inventory Service]
        Order[Order Service]
        Payment[Payment Service]
        Delivery[Delivery Service]
        Notification[Notification Service]
    end

    Kafka[(Kafka)]
    Redis[(Redis)]

    subgraph Databases
        UserDB[(user_db)]
        ProductDB[(product_db)]
        InventoryDB[(inventory_db)]
        OrderDB[(order_db)]
        PaymentDB[(payment_db)]
        DeliveryDB[(delivery_db)]
        NotificationDB[(notification_db)]
    end

    Client --> Gateway
    Gateway --> User
    Gateway --> Product
    Gateway --> Order
    Gateway --> Inventory
    Gateway --> Delivery

    Order -->|REST: reserve/release| Inventory
    Order -->|REST: charge| Payment

    Order -.->|order.created / order.cancelled| Kafka
    Inventory -.->|inventory.reserved / .released / .failed| Kafka
    Payment -.->|payment.completed / .failed| Kafka
    Delivery -.->|shipment.created / delivery.assigned / .completed| Kafka
    Kafka -.-> Order
    Kafka -.-> Delivery
    Kafka -.-> Notification

    User --> UserDB
    Product --> ProductDB
    Inventory --> InventoryDB
    Order --> OrderDB
    Payment --> PaymentDB
    Delivery --> DeliveryDB
    Notification --> NotificationDB

    Product -.cache.-> Redis
```

Solid arrows are synchronous REST calls; dashed arrows are asynchronous Kafka events.

## Why microservices, and why these boundaries

Each service is scoped around a single business capability with a clear owner and a
clear data boundary (see [service-boundaries.md](service-boundaries.md)): identity,
catalog, stock, order orchestration, payment, delivery, and notification. The
boundaries were chosen so that:

- **Order Service never touches another service's database.** It reserves inventory
  and charges payment through their APIs (or reacts to their events), not SQL. This is
  what makes database-per-service actually enforceable — see
  [ADR 001](adr/001-database-per-service.md).
- **Kafka decouples the parts of the order workflow that don't need an immediate
  answer.** Once inventory is reserved and payment succeeds, shipment creation and
  notification delivery are "fire and react," not blocking calls — see
  [ADR 002](adr/002-kafka-for-events.md) and [saga.md](saga.md).
- **No shared domain module.** A shared library of DTOs/entities used by every service
  looks convenient early on and becomes the thing that makes every deploy a
  cross-team coordination problem. Services duplicate small amounts of boilerplate
  (e.g. a standard error response shape) instead of sharing a JAR. The only thing
  services share is the parent Maven POM, and that only manages dependency
  **versions** — it contributes no code.

## Request flow: synchronous vs. asynchronous

- **Synchronous (REST, through the gateway or service-to-service):** used when the
  caller needs an answer before it can proceed — a customer fetching their order
  status, the Order Service asking Inventory to reserve stock before it can move the
  order forward.
- **Asynchronous (Kafka):** used when the producer doesn't need to block on the
  consumer, and when more than one consumer may care about the same fact — e.g.
  `PaymentCompleted` is consumed by both Order Service (to advance the saga) and
  Notification Service (to email the customer), and neither should be able to slow
  down or fail the other.

## Deployment topology (local development)

Every service, plus Postgres, Kafka (KRaft mode, no ZooKeeper), and Redis, runs in
Docker Compose on one bridge network (`smart-delivery-net`). There is no service
registry (Eureka/Consul): the gateway and inter-service calls use static Docker Compose
DNS names, overridable via environment variables. This is a deliberate simplification —
see [ADR discussion below](#deferred-decisions) — appropriate while every service runs
as exactly one instance. Kubernetes is explicitly out of scope until the Docker Compose
system is fully working (see the master engineering brief, section 25).

## Deferred decisions

Documented here so they aren't silently forgotten:

- **Service discovery.** Static DNS via Docker Compose is sufficient for one instance
  per service. If/when services need multiple replicas locally, this is revisited
  (Eureka or Consul, or moving to Kubernetes Services, which give this for free).
- **API Gateway authentication enforcement.** JWT validation at the edge vs. per-service
  is decided in [security.md](security.md) once the User Service issues tokens
  (Phase 2).
- **Prometheus/Grafana/OpenTelemetry.** Stood up in the observability phase (Phase 12),
  not before, so that there's something meaningful to observe.

## Build system

A single Maven multi-module reactor (`pom.xml` at the repository root) aggregates all
eight services for convenience (`mvn clean install` builds and tests everything in one
command, and CI does exactly that). This is a build-time convenience only, not a
runtime coupling — the parent POM contributes dependency and plugin **version**
management (`dependencyManagement`/`pluginManagement`), nothing else. Each module has
its own `pom.xml`, is independently packageable (`mvn -pl <service> -am package`), and
produces its own deployable JAR/Docker image with no dependency on any sibling module's
code.
