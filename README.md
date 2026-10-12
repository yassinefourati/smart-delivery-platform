# Smart Delivery Platform
A microservices-based smart logistics and delivery management platform — a simplified
combination of Amazon-style logistics and food-delivery systems. Customers browse
products, place orders, pay, and track delivery; warehouse managers control inventory;
delivery agents fulfill shipments; admins oversee the platform.

This repository is a production-oriented reference implementation of a Java/Spring Boot
microservices architecture. See docs/architecture.md for the overall design and the
platform capabilities below for the implemented features and deployment caveats.

## Services

| Service | Responsibility | Port |
| --- | --- | --- |
| `api-gateway` | Edge routing, single entry point for clients, one aggregated Swagger UI | 8080 |
| `user-service` | Registration, auth, profiles, addresses, roles | 8081 |
| `product-service` | Product catalog, categories, search | 8082 |
| `inventory-service` | Warehouse stock, reservation, deduction | 8083 |
| `order-service` | Order lifecycle, Saga orchestration | 8084 |
| `payment-service` | Mock payment processing | 8085 |
| `delivery-service` | Shipments, delivery agents, delivery status | 8086 |
| `notification-service` | Kafka-driven customer notifications | 8087 |
| `frontend` (`web`) | React SPA for customers and staff, behind nginx on the API's origin | 8088 |

One more module is not a service: `platform-starter` is a library the six API services
depend on, holding the cross-cutting infrastructure they used to each carry a copy of --
correlation ids, the error contract, resource-server wiring, the transactional outbox,
and OpenAPI metadata. It holds nothing domain-shaped, deliberately; see
ADR 009 for the boundary
and what is intentionally left duplicated.

Each service owns its own PostgreSQL database, is independently buildable
(`mvn -pl <service> -am package`), and communicates with the others only through REST
APIs or Kafka events — never direct database access. See
docs/service-boundaries.md.

API documentation for every service is browsable from one place once the stack is up:
**http://localhost:8080/swagger-ui.html** (docs/api-documentation.md).

## Tech stack

Java 21 · Spring Boot 3.5 · Spring Cloud 2025.0 (Gateway) · PostgreSQL 17 · Apache Kafka
4.3 (KRaft) · Redis 7.4 · Flyway · Resilience4j · Spring Security · JUnit 5 · Mockito ·
Testcontainers · Docker Compose. Full rationale for each choice is in
docs/adr.

## Quick start

Requires Docker and Docker Compose.

```bash
docker compose up -d --build
```

This builds and starts every service plus Postgres, Kafka, and Redis on one shared
Docker network. Each service exposes a health check at
`http://localhost:<port>/actuator/health`; the gateway waits for all backend services
to report healthy before starting. `scripts/seed-demo-data.sh` then fills it with a demo
catalog, stock, customers and orders (safe to re-run). Open **http://localhost:8088** for the web app
(storefront and staff screens; docs/frontend.md). See
docs/local-development.md for running a single service
outside Docker, database access, and troubleshooting.

To build and test everything without Docker:

```bash
mvn clean install
```

## Documentation

- Architecture overview
- Service boundaries & communication rules
- Order flow
- Saga pattern
- Resilience
- Kafka event catalog
- Database design
- Security
- API documentation (one Swagger UI, at the gateway)
- Observability
- Testing -- and load testing
- CI/CD
- Kubernetes
- Security hardening and resilience -- runbooks:
incident response,
rollback and disaster recovery
- Frontend
- Local development
- Architecture Decision Records

## Platform capabilities

The platform implements the order-to-delivery workflow across independently deployed
services. The sections below describe current capabilities rather than the order in
which they were developed.

### Commerce and fulfillment

- **Accounts and access:** registration, JWT authentication, RS256 signing with a JWKS
  endpoint, service credentials, role-based and ownership-based authorization, and
  customer address management.
- **Catalog:** products and categories, public browsing, filtering, sorting, pagination,
  and Redis-backed caching with invalidation on updates.
- **Inventory:** warehouses, stock operations, idempotent reservations and releases,
  optimistic locking, and concurrency protection against overselling.
- **Orders:** a guarded order state machine, server-side price snapshots, idempotent
  checkout requests, ownership-enforced APIs, and live order tracking.
- **Payments and delivery:** mock payment processing and refunds, shipment creation,
  agent assignment, delivery completion, and compensation when fulfillment fails.
- **Notifications:** Kafka-consumed order and delivery events, rendered through a
  replaceable sender interface (currently logged rather than sent to an external provider).
- **Web application:** React 19 and TypeScript storefront and staff screens for catalog,
  checkout, warehouses, inventory, dispatch, and delivery workflows.

### Distributed-system reliability

- **Saga orchestration:** order, inventory, payment, and delivery integration, with
  idempotent steps, compensation, retries, and recovery for stuck orders.
- **Messaging:** Kafka event contracts, bounded consumer retries and dead-letter topics.
- **Transactional outbox:** atomic event persistence, safe multi-replica claiming with
  `FOR UPDATE SKIP LOCKED`, per-aggregate ordering, retry backoff, retention cleanup,
  and monitoring.
- **Resilience:** circuit breakers, bulkheads, rate limiting, and retries for internal
  REST clients; business errors are distinguished from infrastructure failures.
- **Service boundaries:** independently owned PostgreSQL databases, REST/Kafka
  integration, and shared infrastructure auto-configuration in `platform-starter`
  without sharing domain models or database schemas.

### Security and operations

- **API protection:** OAuth2 resource-server validation, asymmetric JWT signing,
  service-to-service credentials, ownership checks, and consistent error responses
  using RFC 7807 with backward-compatible fields.
- **Observability:** correlation IDs across HTTP and Kafka, JSON logging, Prometheus
  metrics, OpenTelemetry traces to Tempo, and provisioned Grafana dashboards and alerts.
- **Kubernetes packaging:** Helm charts, health probes, resource/security settings,
  multi-replica support, and manifest validation in CI.
- **Production-oriented deployment definitions:** network policies, External Secrets,
  TLS verification, dedicated migration identities, CloudNativePG, backup/recovery
  definitions, policy enforcement, image signing, SBOMs, rollout controls, and runbooks.

### Quality and delivery

- **Testing:** JUnit, Mockito, Testcontainers integration tests, concurrent-inventory
  and idempotency tests, saga verification, API gateway routing tests, a Compose-based
  end-to-end smoke test, and frontend tests including Playwright.
- **Quality gates:** JaCoCo coverage thresholds, SpotBugs, ArchUnit, Trivy scans,
  dependency updates, Helm validation, and GitHub Actions pipelines.
- **Performance:** k6 smoke/load/stress/spike/soak profiles, database pool sizing,
  Kafka partitions and consumer concurrency, and isolation of slow remote calls from
  database transactions. See docs/load-testing.md for measured results and limitations.

### Verification and deployment scope

The core business workflow has been exercised with integration and end-to-end tests.
Payment and notification providers are mock implementations. The repository includes
Kubernetes and production-security deployment definitions, but those configurations
have **not been applied to a live cluster**; cloud failover, backup/restore drills, and
real AWS integration remain unverified. Load-test numbers come from a constrained test
environment and should not be interpreted as production capacity guarantees.

For implementation and validation details, see docs/architecture.md,
docs/testing.md, docs/security-hardening.md, docs/kubernetes.md,
docs/observability.md, and docs/load-testing.md.

## License

Apache License 2.0 — see LICENSE.
