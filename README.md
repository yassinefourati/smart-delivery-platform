# Smart Delivery Platform

A microservices-based smart logistics and delivery management platform — a simplified
combination of Amazon-style logistics and food-delivery systems. Customers browse
products, place orders, pay, and track delivery; warehouse managers control inventory;
delivery agents fulfill shipments; admins oversee the platform.

This repository is being built incrementally, milestone by milestone, as a
production-quality reference implementation of a Java/Spring Boot microservices
architecture. See [docs/architecture.md](docs/architecture.md) for the full picture and
[Project status](#project-status) below for what's actually implemented today.

## Services

| Service | Responsibility | Port |
|---|---|---|
| `api-gateway` | Edge routing, single entry point for clients | 8080 |
| `user-service` | Registration, auth, profiles, addresses, roles | 8081 |
| `product-service` | Product catalog, categories, search | 8082 |
| `inventory-service` | Warehouse stock, reservation, deduction | 8083 |
| `order-service` | Order lifecycle, Saga orchestration | 8084 |
| `payment-service` | Mock payment processing | 8085 |
| `delivery-service` | Shipments, delivery agents, delivery status | 8086 |
| `notification-service` | Kafka-driven customer notifications | 8087 |

Each service owns its own PostgreSQL database, is independently buildable
(`mvn -pl <service> -am package`), and communicates with the others only through REST
APIs or Kafka events — never direct database access. See
[docs/service-boundaries.md](docs/service-boundaries.md).

## Tech stack

Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 (Gateway) · PostgreSQL 17 · Apache Kafka
4.3 (KRaft) · Redis 7.4 · Flyway · Resilience4j · Spring Security · JUnit 5 · Mockito ·
Testcontainers · Docker Compose. Full rationale for each choice is in
[docs/adr](docs/adr).

## Quick start

Requires Docker and Docker Compose.

```bash
docker compose up -d --build
```

This builds and starts every service plus Postgres, Kafka, and Redis on one shared
Docker network. Each service exposes a health check at
`http://localhost:<port>/actuator/health`; the gateway waits for all backend services
to report healthy before starting. See [docs/local-development.md](docs/local-development.md)
for running a single service outside Docker, database access, and troubleshooting.

To build and test everything without Docker:

```bash
mvn clean install
```

## Documentation

- [Architecture overview](docs/architecture.md)
- [Service boundaries & communication rules](docs/service-boundaries.md)
- [Order flow](docs/order-flow.md)
- [Saga pattern](docs/saga.md)
- [Kafka event catalog](docs/kafka-events.md)
- [Database design](docs/database-design.md)
- [Security](docs/security.md)
- [Observability](docs/observability.md)
- [Local development](docs/local-development.md)
- [Architecture Decision Records](docs/adr)

## Project status

Built incrementally; each milestone lands only after it builds and its tests pass.

- [x] **Phase 1 — Foundation**: multi-module Maven build, Docker Compose environment
      (Postgres/Kafka/Redis + all service skeletons), architecture documentation.
- [ ] Phase 2 — User service (auth, JWT, roles, addresses)
- [ ] Phase 3 — Product service (catalog, search, Redis cache)
- [ ] Phase 4 — Inventory service (reservation, optimistic locking)
- [ ] Phase 5 — Order service (state machine, idempotency)
- [ ] Phase 6 — Kafka event contracts & producers/consumers
- [ ] Phase 7 — Saga orchestration (order → inventory → payment → shipment)
- [ ] Phase 8 — Transactional outbox
- [ ] Phase 9 — Delivery service
- [ ] Phase 10 — Notification service
- [ ] Phase 11 — Resilience4j (circuit breaker, retry, bulkhead, rate limiter)
- [ ] Phase 12 — Observability (Prometheus, Grafana, OpenTelemetry, correlation IDs)
- [ ] Phase 13 — Integration test suite (Testcontainers)
- [ ] Phase 14 — CI/CD

Currently every service is a minimal Spring Boot application exposing
`/actuator/health`, `/actuator/info`, and `/actuator/metrics` — no business logic yet.
This proves the build, containerization, and orchestration are correct before any
domain code is added on top.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
