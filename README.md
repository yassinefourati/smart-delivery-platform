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
- [Resilience](docs/resilience.md)
- [Kafka event catalog](docs/kafka-events.md)
- [Database design](docs/database-design.md)
- [Security](docs/security.md)
- [Observability](docs/observability.md)
- [Testing](docs/testing.md)
- [Local development](docs/local-development.md)
- [Architecture Decision Records](docs/adr)

## Project status

Built incrementally; each milestone lands only after it builds and its tests pass.

- [x] **Phase 1 — Foundation**: multi-module Maven build, Docker Compose environment
      (Postgres/Kafka/Redis + all service skeletons), architecture documentation.
- [x] **Phase 2 — User service**: registration, JWT login, BCrypt password hashing,
      role-based + ownership-based endpoint authorization, address book CRUD, Flyway
      schema, unit + Testcontainers integration tests.
- [x] **Phase 3 — Product service**: category + product CRUD (ADMIN-only writes,
      publicly browsable reads), pagination/sorting/category/price/text-search
      filtering, Redis-backed product cache with write-through invalidation, unit +
      Testcontainers (Postgres + Redis) integration tests.
- [x] **Phase 4 — Inventory service**: warehouse + stock management, reserve/release/
      deduct lifecycle (idempotent per order+product), optimistic locking with a
      retry orchestrator that absorbs lock contention without overselling or
      spuriously failing legitimate concurrent reservations, unit + Testcontainers
      integration tests including the two-customers-one-unit concurrency scenario.
- [x] **Phase 5 — Order service**: order/order-item domain with an explicit state
      machine (guards every transition, not just cancel), product-service price/
      availability snapshotting at creation time, Idempotency-Key support with
      request-fingerprint mismatch detection, ownership-enforced read/cancel/list
      APIs, unit + Testcontainers integration tests.
- [x] **Phase 6 — Kafka**: shared JSON envelope contract (independently duplicated per
      service, not a shared library -- plain-string (de)serialization to avoid
      cross-service Java type-header coupling), real producers (order-service:
      created/cancelled; inventory-service: reserved/released/failed) and a real
      consumer (order-service reacting to inventory/payment/shipment/delivery events
      by idempotently advancing its own state machine), bounded retry + dead-letter
      topics. Direct `KafkaTemplate.send()` for now, not yet the outbox pattern --
      flagged, fixed in Phase 8.
- [x] **Phase 7 — Saga orchestration**: payment-service built from scratch (mock
      provider with a deterministic decline threshold, Payment/PaymentTransaction,
      idempotent per orderId, refund support); `OrderSagaOrchestrator` drives
      Order → Inventory → Payment via real REST calls (a short-lived internal
      `SERVICE`-role JWT authenticates them), reusing Phase 6's idempotent transition
      handlers so the synchronous fast path and the async Kafka backstop can never
      conflict; full compensation (partial-reservation rollback, payment-decline
      release, paid-order-cancellation refund); the whole saga is retry-safe end to
      end because every step is independently idempotent. Order → Payment → Shipment
      remained untriggered until delivery-service was built (Phase 9).
- [x] **Phase 8 — Transactional outbox**: `OrderEventPublisher`, `InventoryEventPublisher`,
      and `PaymentEventPublisher` now write an `OutboxEvent` row inside the same
      transaction as the business change they announce, instead of calling
      `KafkaTemplate.send()` directly; a separate `@Scheduled` `OutboxPublisher` per
      service polls `PENDING` rows and actually sends them to Kafka, closing the
      commit-then-crash-before-publish gap flagged since Phase 6. Publishing moved from
      controllers into the `@Transactional` service methods themselves (`OrderService`,
      `PaymentService`, `InventoryReservationOperations`) so the outbox write is
      genuinely atomic with the change it describes.
- [x] **Phase 9 — Delivery service**: built from scratch -- `DeliveryAgent`/`Shipment`/
      `Delivery` domain, all three writing through the transactional outbox from day
      one (no direct-`KafkaTemplate.send()` phase to retrofit, unlike Phases 6-7's
      producers). `PaymentCompletedListener` reacts to `payment.completed` to create a
      `Shipment` per order (idempotent, unique on `orderId`); an ADMIN assigns a
      `DeliveryAgent` (idempotent for a repeat request to the same agent, a real
      conflict for a different one); that agent marks the delivery complete
      (idempotent, ownership-enforced -- a `DELIVERY_AGENT` can only touch their own
      assignments, mirroring `/api/v1/orders/user/{userId}`'s ownership pattern).
      Publishes `shipment.created`/`delivery.assigned`/`delivery.completed`, which
      order-service has consumed since Phase 6 without needing any change here -- the
      saga's Payment → Shipment → Delivery leg is real end to end for the first time.
- [x] **Phase 10 — Notification service**: the platform's other consumer of every topic
      in the catalog (10, alongside order-service's subset) -- and the only service that
      publishes none of its own. No database, no REST API, no Spring Security: nothing
      calls it and it owns no data (see docs/service-boundaries.md). `NotificationEventListener`
      renders each event into a human-readable line, delegated to `NotificationSender`
      (the mock boundary, logs at `INFO`; a real deployment would call an email/SMS/push
      provider here, same pattern as `MockPaymentProvider`). Found and fixed a real,
      previously-latent bug while building this: a monetary `BigDecimal` round-tripped
      through an envelope's untyped `JsonNode` payload doesn't reliably keep its original
      scale, so rendering one for a human now always uses explicit `%.2f` formatting
      instead of the value's own (unreliable) `toString()` -- see docs/kafka-events.md.
- [x] **Phase 11 — Resilience4j**: circuit breaker, retry, bulkhead, and rate limiter on
      order-service's three synchronous REST clients (`ProductServiceClient`,
      `InventoryServiceClient`, `PaymentServiceClient`) -- the only service in the
      platform that makes a synchronous call to another service. One
      breaker/bulkhead/limiter instance per downstream, shared across all of that
      downstream's operations. `InsufficientStockException` (a 409, a real business
      outcome) is explicitly exempted from the `inventory-service` breaker and retry so
      a routine "no stock" answer never trips resilience machinery meant for actual
      outages -- see docs/resilience.md. No fallback methods: the saga's calls
      propagate any Resilience4j rejection into Spring Kafka's existing retry/DLT
      handling exactly like any other infrastructure failure (saga resumability,
      untouched); the synchronous product-service call maps the same rejections to a
      `503` via `GlobalExceptionHandler`. `ResilienceIntegrationTest` is the one
      integration test in this codebase that actually runs in this sandbox (no
      Postgres/Kafka needed) and verifies both directions: repeated business failures
      never open the circuit, repeated infrastructure failures do. Also found and
      worked around a real dependency-resolution bug: `resilience4j-spring-boot3`'s
      nominal latest release pulls in a version-inconsistent `resilience4j-spring6`
      that fails to boot; pinned to a fully self-consistent `2.2.0` instead, confirmed
      by that same test actually passing.
- [x] **Phase 12 — Observability**: gateway-assigned `correlationId`, propagated via
      `X-Correlation-Id` through every REST call and Kafka event, into the SLF4J MDC and
      onto the current tracing span in every backend service (`CorrelationIdFilter`);
      structured JSON logs in the `docker` profile. `micrometer-registry-prometheus` on
      all 8 services, scraped by a new `prometheus` Compose service. Distributed tracing
      via `micrometer-tracing-bridge-otel` exporting OTLP to a new `tempo` Compose
      service, every request sampled. A new `grafana` Compose service, provisioned (not
      clicked together by hand) with Prometheus/Tempo datasources and a starter
      dashboard: request rate/error rate/p95 latency per service, JVM heap/GC, HikariCP
      pool saturation, and order processing outcomes/failure rate (a new
      `order.saga.outcomes` counter). Kafka consumer lag panel scoped out -- would need
      its own exporter container, nothing else here depends on it. WebFlux's
      thread-hopping means api-gateway's own filter can't rely on MDC or
      `Tracer.currentSpan()` the way every blocking backend service does -- logs the
      correlation id explicitly instead. See [docs/observability.md](docs/observability.md),
      including its disclosed verification caveat: Docker is unavailable in this sandbox,
      so the compose stack's dashboards were never actually rendered against live data.
- [x] **Phase 13 — Integration test suite**: an audit, not a rebuild -- every service
      phase since Phase 4 already built its own Testcontainers integration coverage as
      it went (real Postgres and/or Kafka, not mocks), including a genuine concurrency
      test (inventory-service, two threads racing for the last unit of stock at a
      `CyclicBarrier`) and a real end-to-end saga test over a live Kafka broker
      (order-service). The one real gap this phase closed: api-gateway had only a
      context-load smoke test. `ApiGatewayRoutingIntegrationTest` now starts the actual
      gateway and proves its routing table and `CorrelationIdGlobalFilter` against an
      embedded stub HTTP server (no new dependency, no Testcontainers needed --
      api-gateway has no database or broker of its own). See
      [docs/testing.md](docs/testing.md), including its disclosed caveat: Docker is
      unavailable in this sandbox, so most `*IntegrationTest` classes are verified to
      compile and pass in CI, not run to completion here -- only the two integration
      tests needing no containers (this new one and `ResilienceIntegrationTest`) have
      actually been executed and confirmed passing in this environment.
- [ ] Phase 14 — CI/CD

All eight backend services now have real business logic end to end. Placing an order
actually reserves inventory, charges a (mock) payment, creates a shipment, can be
carried through assignment and delivery by a real agent, and generates a logged
notification at every step along the way -- with full compensation on failure up
through payment -- see [docs/saga.md](docs/saga.md). `api-gateway` has routed to every
service's real API since each was built (it owns no business logic of its own by
design -- see [docs/architecture.md](docs/architecture.md)); its delivery-service route
predicates were corrected this phase to match the real `/api/v1/agents`,
`/api/v1/shipments`, and `/api/v1/deliveries` paths built in Phase 9, which the
placeholder route from before that phase existed didn't match.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
