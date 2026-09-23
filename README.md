# Smart Delivery Platform

[![CI](https://github.com/yassinefourati/smart-delivery-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/yassinefourati/smart-delivery-platform/actions/workflows/ci.yml)

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
[ADR 009](docs/adr/009-platform-starter-and-the-shared-code-boundary.md) for the boundary
and what is intentionally left duplicated.

Each service owns its own PostgreSQL database, is independently buildable
(`mvn -pl <service> -am package`), and communicates with the others only through REST
APIs or Kafka events — never direct database access. See
[docs/service-boundaries.md](docs/service-boundaries.md).

API documentation for every service is browsable from one place once the stack is up:
**<http://localhost:8080/swagger-ui.html>** ([docs/api-documentation.md](docs/api-documentation.md)).

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
to report healthy before starting. Then open **<http://localhost:8088>** for the web app
(storefront and staff screens; [docs/frontend.md](docs/frontend.md)). See
[docs/local-development.md](docs/local-development.md) for running a single service
outside Docker, database access, and troubleshooting.

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
- [API documentation (one Swagger UI, at the gateway)](docs/api-documentation.md)
- [Observability](docs/observability.md)
- [Testing](docs/testing.md)
- [CI/CD](docs/ci-cd.md)
- [Kubernetes](docs/kubernetes.md)
- [Frontend](docs/frontend.md)
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
- [x] **Phase 14 — CI/CD**: `docker-build` now publishes each service's image to GHCR
      (`ghcr.io/yassinefourati/smart-delivery-platform/<service>:latest` and
      `:<commit-sha>`), gated to only fire on a push to `main` -- a PR (including one
      from a fork) still gets a real build, proving the `Dockerfile` works, but never
      publishes. Uses the workflow's own `GITHUB_TOKEN`, no extra secret needed, scoped
      to `packages: write` at the job level only. Added `.github/dependabot.yml` (weekly
      Maven, Docker base image, and GitHub Actions updates) and a CI status badge to
      this README. No deploy step: there's no Kubernetes manifest, Helm chart, or cloud
      environment anywhere in this repository to deploy *to* -- see
      [docs/ci-cd.md](docs/ci-cd.md) for that scoping decision, made the same way
      Phase 12's Kafka-lag panel and Phase 1's static analysis were deferred rather than
      built against nothing real.
- [x] **Phase 15 — Outbox hardening**: the Phase 8 poller was only correct with exactly
      one instance of a service running, which nothing enforced. Three fixes, identical in
      all four publishing services (order, inventory, payment, delivery) -- see
      [ADR 006](docs/adr/006-outbox-concurrency-and-ordering.md). (1) `OutboxPublisher`
      now *claims* its batch with a locking query inside a transaction
      (`FOR UPDATE SKIP LOCKED`) instead of reading rows anybody else could read too, so
      two replicas can no longer publish the same row. (2) That claim is restricted to the
      **oldest unpublished event per aggregate**, ordered by a new database-assigned
      `sequence_no` rather than by `created_at` (which ties between rows written in one
      transaction). This makes per-aggregate ordering a guarantee rather than an accident:
      previously a failed send for an order's first event didn't stop its second from
      going out ahead of it, and two replicas could each grab a different event of the
      same order. (3) An `OutboxCleanupJob` reclaims `PUBLISHED` rows past
      `outbox.retention` (default 7d) in bounded, concurrency-safe batches -- nothing had
      ever deleted one, so every event every service had published was still in its
      database. Failed rows also back off exponentially now (`next_attempt_at`) instead of
      being retried every two seconds forever; retries stay *unbounded*, since abandoning
      a row would silently drop an event whose business change is already committed. Five
      new metrics per service and five new Grafana panels make a stuck outbox visible for
      the first time. The four implementations were byte-for-byte identical apart from
      their package declaration -- infrastructure duplication, not the deliberate
      event-contract duplication of [ADR 002](docs/adr/002-kafka-for-events.md), and the
      main argument for the shared platform starter Phase 19 weighed, and then built.
- [x] **Phase 16 — Asymmetric JWT signing**: every service used to verify tokens with the
      same HMAC secret, which meant every service that could *verify* an ADMIN token could
      also *forge* one -- one compromised service, however unimportant, was enough to
      impersonate anyone anywhere. user-service is now the only token issuer, signing
      RS256 with a private key nothing else has, and publishing the public half at
      `GET /.well-known/jwks.json` (routed through the gateway; public by design, since a
      resource server must fetch keys before it can authenticate anything). Every other
      service became a standard Spring Security OAuth2 resource server -- three properties
      instead of a hand-written `JwtService` and `JwtAuthenticationFilter`, both now
      deleted everywhere along with jjwt itself. A `JwtAuthenticationConverter` keeps the
      `roles` claim mapping to `ROLE_*` and the principal name as the user id, so every
      existing `@PreAuthorize` ownership check works unchanged, and the existing 401/403
      handlers are wired into the resource server so error bodies are byte-for-byte what
      they were: this phase changes how tokens are *trusted*, not who can do what. Tokens
      carry a `kid` and the JWKS can publish retired keys, so rotation is a configuration
      change rather than a flag day. order-service's self-minted `SERVICE` tokens
      (`InternalServiceTokenProvider`) are gone, replaced by a real client-credentials
      grant against a new `POST /api/v1/auth/service-token`, with a per-service credential
      and a token cache that refreshes ahead of expiry. See
      [ADR 007](docs/adr/007-asymmetric-jwt-signing.md).

      Getting a green build for this phase also meant running the Testcontainers suite
      for the first time in a while, and that turned up **five pre-existing failures**
      that had been invisible because CI's reactor stopped at the second module. Two were
      real production bugs, not test bugs: order-service returned `500` on every order
      read (`LazyInitializationException` on a lazy collection mapped outside its
      transaction), and the saga never advanced past `CREATED`, because
      `OrderSagaOrchestrator` invoked its own `@Transactional` methods on itself, which a
      proxy-based annotation does nothing about -- so no order had ever reached `PAID`.
      All five are fixed and described in [docs/testing.md](docs/testing.md); none of them
      were caused by this phase, and each was reproduced on the commit before it. With
      Docker still unavailable here, Postgres, Redis, and a Kafka broker were installed
      and run directly in the sandbox instead, and all twelve integration test classes now
      pass against them.
- [x] **Phase 17 — Reliable compensation and a stuck-saga reaper**: two ways an order
      could end up permanently wrong, both the same shape -- something had to happen next
      and nothing owned making it happen. (1) Cancellation compensation ran inline in the
      cancel request, *after* the CANCELLED status had committed, with every exception
      caught and logged: a failed refund, or a pod dying in that window, left an order
      cancelled with its stock still held and its payment still taken, and nothing that
      would ever retry. `OrderService.cancel` now writes the `order.cancelled` outbox row
      in the same transaction with a new `previousStatus` field, and a new
      `OrderCancellationListener` compensates off that event -- so "cancelled" and "will
      be compensated for" are one atomic fact, failures propagate into the existing retry
      and dead-letter handling instead of a log line, and the HTTP response is unchanged.
      (2) A saga that exhausted its Kafka retries simply stopped, leaving the order in
      whichever state it reached, holding reservations forever, with nothing able to tell
      it apart from an order progressing slowly. `StuckSagaReaper` claims such orders
      (`FOR UPDATE SKIP LOCKED`, the same mechanism as the outbox poller) and either
      re-runs the saga -- which every step is idempotent enough to resume -- or gives up:
      release, refund if charged, mark FAILED, and announce it on a new `order.failed`
      topic. Incrementing the new `saga_attempts` column refreshes `updated_at`, so the
      claim doubles as a lease and two reaper instances never take the same order. `FAILED`
      is now reachable from every state a saga can stall in; before, an order stuck in
      `INVENTORY_RESERVED` had no terminal state at all. Three new metrics and two Grafana
      panels -- `saga.stuck.count` is the one that matters, since nothing else here could
      show an order that had quietly stopped. See
      [ADR 008](docs/adr/008-reliable-compensation-and-stuck-saga-reaper.md).
- [x] **Phase 18 — Quality gates and an end-to-end smoke test**: everything here exists to
      catch a class of bug the existing tests structurally could not. **JaCoCo** floors are
      *measured, not chosen* — the full suite was run, per-module line coverage read off the
      report, and each floor set to that number rounded down (87-96%, see
      [docs/ci-cd.md](docs/ci-cd.md#line-coverage-jacoco)); the gate's job is "do not go
      backwards", not "reach 80%". **SpotBugs** at `Max`/`Medium` produced 88 findings: 87
      `EI_EXPOSE_REP` and one `CT_CONSTRUCTOR_THROW` are excluded as whole categories with
      written justifications in `spotbugs-exclude.xml` (never per class, so a genuine new
      instance still surfaces), because defensive-copying Spring-injected collaborators and
      Hibernate-managed collections would break the frameworks this is built on. The 88th was
      real and is fixed: `PaymentServiceClient` dereferenced the response body without a null
      check, so a 2xx with an empty body would have been read as a *failed* charge and
      compensated — releasing stock for an order that may well have been charged. **ArchUnit**
      rules in every service pin the layering, the important one being that only `event`
      packages may touch `KafkaTemplate` — the rule that keeps the outbox from being bypassed.
      notification-service gets bespoke rules rather than `allowEmptyShould`, which would have
      turned "matched no classes" into a silent pass for the other six. **Trivy** scans each
      image before it can be published, failing on CRITICAL only and deliberately so
      ([why](docs/ci-cd.md#why-critical-only)). Finally, `scripts/e2e-smoke.sh` drives the real
      compose stack **through the gateway only** — register, log in, stock a product, place an
      order and follow it to `PAID`, replay the `Idempotency-Key` and get the same order, blow
      past available stock and watch the reservations come back, cancel a paid order and watch
      the payment refund — dumping `docker compose logs` as a CI artifact when it fails. That
      required a first admin to exist, so user-service gained a `BootstrapAdminInitializer`
      gated on two environment variables being set (deliberately *not* declared in
      `application.yml`, since `@ConditionalOnProperty` reads a blank value as present).
      Running it found **two more pre-existing bugs**, neither caused by this phase and
      neither catchable by any test that existed before it: none of the eight jars were
      executable — the build imports the Spring Boot BOM instead of inheriting from
      `spring-boot-starter-parent`, so `spring-boot-maven-plugin` had no `repackage`
      execution and every published image would have died with `no main manifest attribute`,
      which CI never noticed because `docker-build` only ever *built* images and never started
      one — and `/api/v1/warehouses/**` had never been routed through the gateway at all, so
      warehouse management only worked if you bypassed the front door. Both fixed, both
      covered. See [docs/ci-cd.md](docs/ci-cd.md) and
      [docs/testing.md](docs/testing.md#two-more-pre-existing-bugs-found-in-phase-18).
- [x] **Phase 19 — A platform starter, and where the shared-code boundary sits**: eight
      services built from the same template by copy-and-paste had accumulated nearly 4,000
      lines of byte-identical infrastructure -- `CorrelationIdFilter` and `ErrorResponse` in
      six copies, the JWT converter, entry point and access-denied handler in six, all seven
      outbox classes in four, and an `OpenApiConfig` differing only in its title string in
      six. The cost was never the typing; it was that a fix has to be applied *n* times with
      nothing noticing when it is applied fewer. This platform had already paid that bill
      twice -- the dead-letter suffix bug, and the Phase 15 outbox rewrite, a
      correctness-critical `FOR UPDATE SKIP LOCKED` claim query that had to land identically
      in four services. [ADR 006](docs/adr/006-outbox-concurrency-and-ordering.md) said so at
      the time. A new `platform-starter` module now holds that infrastructure once, wired in
      by Spring Boot **auto-configuration** rather than a base class or a component scan:
      every bean is `@ConditionalOnMissingBean` so a service can always take the wheel back,
      every dependency is `<optional>`, and every auto-configuration is `@ConditionalOnClass`
      -- so api-gateway (reactive, its own correlation filter) and notification-service (no
      database, no outbox) depend on none of it, which is a sign the boundary is roughly
      right rather than a sign it failed. What deliberately did **not** move is the more
      important half, and [ADR 009](docs/adr/009-platform-starter-and-the-shared-code-boundary.md)
      spends more words on it: event payloads and `EventEnvelope` stay duplicated per service
      ([ADR 002](docs/adr/002-kafka-for-events.md)), because a shared events module makes the
      wire format a compile-time dependency and turns a microservices platform into a
      distributed monolith; so do each service's `SecurityFilterChain` (the *wiring* is
      shared, the *policy* is not), its domain exceptions, and its Flyway migrations --
      `outbox_events`'s DDL included, since each service owns its own schema.
      Errors moved to RFC 7807 `ProblemDetail`, **additively**: the body now carries `type`,
      `title`, `detail` and `instance` alongside the five fields clients have read since
      Phase 2, with the same names and values, so nothing that parsed the old shape has to
      change -- the same "add fields, never rename or remove" rule the event payloads follow,
      with a test that fails if someone later "tidies up" by dropping them. Consolidating the
      handler surfaced a real pre-existing defect: malformed JSON, an unparseable path
      variable, and a request to a nonexistent path all fell through to the catch-all and
      came back as **500s**, telling a caller who sent bad input that the server had failed.
      Fixed in the one place that decision is now made. 401s and 403s also stopped minting a
      random `correlationId` -- they are produced inside the security filter chain and had
      been ignoring the request's actual id, so the one field whose job is to join a client's
      report to a log line matched nothing on exactly the responses people most often ask
      about. **API documentation is now one Swagger UI at the gateway**
      ([docs/api-documentation.md](docs/api-documentation.md)) with a dropdown to switch
      between services: each service still generates its own document, the gateway proxies
      and aggregates them, and the per-service UIs are switched off. The detail that makes it
      work rather than merely exist is the `servers` entry -- left to itself springdoc would
      advertise `http://order-service:8084`, a hostname that resolves to nothing in a
      browser, so every "Try it out" would fail from a UI that looked perfectly fine.
- [x] **Phase 20 -- Kubernetes readiness**: every service can now run as more than one
      replica behind real probes, and a Helm chart exists to put it there -- but the
      headline is what *would* have gone wrong. `requestMatchers("/actuator/health")`
      matches that exact path and nothing below it, so the kubelet's unauthenticated
      `GET /actuator/health/liveness` would have got a `401` and **every replica of all
      six secured services would have restarted forever**, while the two services with no
      security chain stayed up -- a CrashLoopBackOff that reads as a cluster fault and is a
      one-line authorization problem. Checking that turned up a **pre-existing bug:
      Prometheus has been getting `401` from six of eight services since Phase 12**, for the
      same exact-match reason, so those Grafana panels could never have shown data. The
      probe design ([ADR 010](docs/adr/010-probe-and-lifecycle-contract.md)) keeps every
      shared dependency out of both liveness and readiness, and it was **tested live by
      stopping Postgres under the running services**: liveness stayed UP on all eight, so
      no kubelet would have restarted anything, while the naive recipe (liveness on
      `/actuator/health`) would have restarted all six database services at once into a
      database that was still down. That test caught a second bug -- an unreachable
      database returned `500 INTERNAL_ERROR` with a stack trace per request, not a
      retryable `503`, which the ADR's own argument had assumed. Fixed, and the same test
      then returned `503` in two seconds with one log line; with Postgres back, the same
      process served `200` within a second, never restarted. The two seconds is itself a
      fix: HikariCP's 30-second default would have parked the Tomcat threads that also serve
      the probes, reproducing the restart storm *despite* the probe design; and its default
      pool of 10 connections x 6 services x 2 replicas exceeds Postgres's 100. Also caught:
      a single scheduler thread that let one slow outbox batch **starve the stuck-saga
      reaper** entirely; a non-numeric `USER` that would stop every pod under
      `runAsNonRoot`; and a `chown` that **shipped every image's jar twice (96MB on
      order-service)**. The chart lints, renders with both values files, validates against
      the published Kubernetes schemas (75 of 77 objects; the other two are CRDs with no
      published schema), and carries seven render-time guards -- one refuses to scale
      user-service without a shared signing key, which would otherwise give each replica a
      different key under the same `kid` and fail roughly half of all tokens, forever. A new
      `helm-chart` CI job keeps it rendering. **None of it has been applied to a cluster**:
      there is no cluster. See [docs/kubernetes.md](docs/kubernetes.md).
- [x] **Phase 21 -- The frontend**: a React 19 + TypeScript single-page app covering all
      four personas -- a storefront with live stock, cart, checkout and a live order
      tracker, and staff screens for catalog, warehouses and stock, dispatch, order
      lookup and delivery completion -- served from the API's own origin by an
      unprivileged nginx ([ADR 011](docs/adr/011-same-origin-react-spa.md)), with the
      token held in memory only ([ADR 012](docs/adr/012-access-token-in-memory-only.md))
      and every response validated at the boundary
      ([ADR 013](docs/adr/013-boundary-validated-api-contract.md)). The part that
      mattered most is the `Idempotency-Key`: minted when checkout opens, never in the
      click handler, reused across double-clicks, retries, refreshes and the re-login a
      token expiry forces, and rotated only when the order itself changes -- with a test
      that records every key the server sees in each of those cases. **Building it
      against the live platform found a real backend bug**: two concurrent requests with
      the same key made order-service return `500` instead of replaying, because its
      recovery path re-read the winning order inside a transaction PostgreSQL had
      already aborted, so the one situation the key exists for -- a double-click -- failed.
      Fixed with an explicit transaction boundary, proven by a new integration test that
      forces the race against real PostgreSQL and fails on the old code with the exact
      error the walk-through logged, and confirmed live (four concurrent POSTs, one order,
      `order_idempotency_concurrent_replays_total` = 3). The screens say what the API
      cannot do rather than papering over it: stock rows are create-only, there is no
      all-orders endpoint (so order lookup is deliberately not a half-list that would hide
      stuck orders), and agents cannot see addresses. 347 frontend tests; a Playwright
      walk-through of the built bundle through the production nginx config against all
      eight services; new `frontend` CI job, image in the build/scan/publish matrix, and
      three web-tier assertions in the smoke test. The Docker image itself was not built
      here (no Docker daemon); CI builds and scans it. See
      [docs/frontend.md](docs/frontend.md).

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
