# Testing

## Strategy

Two layers, consistently across all 8 services:

- **Unit tests** — service/domain logic with Mockito-mocked collaborators. Fast, no
  infrastructure, run everywhere including this sandbox.
- **Integration tests** — a real Spring context against real infrastructure via
  [Testcontainers](https://testcontainers.com/): real Postgres, real Kafka, or both,
  per service. These prove the actual JPA mappings, Flyway migrations, Kafka
  (de)serialization, and Spring Security filter chain work, not just the code that sits
  between them.

There is no separate "integration test suite" module or phase-13-only test class —
every service phase built its own integration coverage as it went (Phase 4's
concurrency test, Phase 6 onward's Kafka tests, Phase 7's saga test, and so on). This
phase's job was to audit that coverage for gaps and close the one real one: api-gateway.

## What's covered, per service

| Service | Unit tests | Integration tests (Testcontainers) |
|---|---|---|
| user-service | `AuthServiceTest`, `UserServiceTest`, `JwtServiceTest` | `UserApiIntegrationTest` (Postgres) |
| product-service | `ProductServiceTest`, `CategoryServiceTest` | `ProductApiIntegrationTest` (Postgres) |
| inventory-service | reservation/warehouse/admin service tests, `InventoryTest` (domain) | `InventoryApiIntegrationTest` (Postgres) — includes a genuine concurrency test: two reservation requests for the last unit of stock fired from separate threads at a `CyclicBarrier`, asserting exactly one wins and the other sees a real conflict, not a mocked one |
| order-service | `OrderServiceTest`, `OrderSagaOrchestratorTest`, `OrderSagaEventHandlerTest`, `RequestFingerprintTest`, `OrderStatusTest`, publisher/outbox tests | `OrderApiIntegrationTest` (Postgres), `OrderKafkaIntegrationTest` (Postgres+Kafka), `OrderSagaIntegrationTest` (Postgres+Kafka — the whole Order→Inventory→Payment saga end to end over a real broker, with `MockRestServiceServer` standing in for the two downstream services so order-service stays independently testable), `ResilienceIntegrationTest` (no Testcontainers — a narrow Spring context slice, see [resilience.md](resilience.md)) |
| payment-service | `MockPaymentProviderTest`, `PaymentServiceTest`, publisher/outbox tests | `PaymentApiIntegrationTest` (Postgres) |
| delivery-service | `DeliveryServiceTest`, `ShipmentServiceTest`, `DeliveryAgentServiceTest`, publisher/outbox/listener tests | `DeliveryApiIntegrationTest` (Postgres) |
| notification-service | `NotificationEventListenerTest` | `NotificationEventListenerIntegrationTest` (Kafka only — no database, see [service-boundaries.md](service-boundaries.md)) |
| api-gateway | — (no business logic to unit-test; see [architecture.md](architecture.md)) | `ApiGatewayRoutingIntegrationTest` |

## api-gateway's test (new this phase)

Before this phase, api-gateway had only `ApiGatewayApplicationTests` — a bare
`@SpringBootTest` context-load check that exercises none of the gateway's own code.
`ApiGatewayRoutingIntegrationTest` closes that gap: it starts the real gateway
(`webEnvironment = RANDOM_PORT`) and points every downstream service URI at one embedded
stub HTTP server (JDK's own `com.sun.net.httpserver`, not a new test dependency, and
not Testcontainers — the gateway has no database or broker of its own to containerize).
The stub records the path and `X-Correlation-Id` header it receives, so the test proves,
against the gateway's actual routing table and actual `CorrelationIdGlobalFilter`, not a
mock of either:

- a request is routed to the correct downstream path (`Path=` predicates in
  `application.yml` actually match and forward correctly);
- a correlation id is generated and echoed back when the client didn't send one;
- a client-supplied correlation id is forwarded to the downstream call unchanged.

Because it needs no Postgres/Kafka container, this is one of only two integration
tests in the whole platform (`ResilienceIntegrationTest` is the other) that actually
runs to completion in this sandbox — see the Docker caveat below.

## Docker / Testcontainers caveat

This platform was built in a sandbox with no Docker available (confirmed repeatedly
across every phase via proxy 403s reaching Docker Hub). Every `*IntegrationTest` that
needs Postgres and/or Kafka has been verified to *compile* here, and to pass in CI
(`.github/workflows/ci.yml` runs `mvn -B clean verify` on a GitHub Actions runner, which
does have Docker), but has not been run to completion in this environment. Only
`ApiGatewayRoutingIntegrationTest` and `ResilienceIntegrationTest` — the two that need
no containers — have actually been executed and confirmed passing here.

If you're picking this repository up locally with Docker available, running
`mvn clean verify` (the full reactor, no `-Dtest` filter) is the first thing worth doing
to confirm that caveat doesn't hide a real problem.
