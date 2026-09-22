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

Phase 18 added two layers on top, both of which run in the same `mvn -B clean verify`:

- **Architecture tests** — [ArchUnit](https://www.archunit.org/) rules, one
  `ArchitectureTest` per service, asserting the layering rather than trusting it. See
  [below](#architecture-tests-phase-18).
- **An end-to-end smoke test** — `scripts/e2e-smoke.sh`, outside the Maven build: the
  real `docker-compose.yml` stack, driven **through the gateway only**, as a client would.
  See [docs/ci-cd.md](ci-cd.md#end-to-end-smoke-test).

Coverage and static analysis gate the same build — JaCoCo floors measured per module,
SpotBugs at `Medium`/`Max`. Both are documented in
[docs/ci-cd.md](ci-cd.md#quality-gates-phase-18) rather than here, since their thresholds
are a CI policy question.

There is no separate "integration test suite" module or phase-13-only test class —
every service phase built its own integration coverage as it went (Phase 4's
concurrency test, Phase 6 onward's Kafka tests, Phase 7's saga test, and so on). This
phase's job was to audit that coverage for gaps and close the one real one: api-gateway.

## What's covered, per service

| Service | Unit tests | Integration tests (Testcontainers) |
|---|---|---|
| user-service | `AuthServiceTest`, `UserServiceTest`, `JwtServiceTest` (RS256 signing, `kid`, claims, round-tripped through a real `JwtDecoder`), `JwtKeyProviderTest` (key loading, rotation, no private material in the JWKS), `ServiceTokenServiceTest` | `UserApiIntegrationTest` (Postgres — now also the JWKS endpoint, the client-credentials grant, and an old-HMAC token being rejected) |
| product-service | `ProductServiceTest`, `CategoryServiceTest` | `ProductApiIntegrationTest` (Postgres) |
| inventory-service | reservation/warehouse/admin service tests, `InventoryTest` (domain) | `InventoryApiIntegrationTest` (Postgres) — includes a genuine concurrency test: two reservation requests for the last unit of stock fired from separate threads at a `CyclicBarrier`, asserting exactly one wins and the other sees a real conflict, not a mocked one |
| order-service | `OrderServiceTest`, `OrderSagaOrchestratorTest`, `OrderSagaEventHandlerTest`, `RequestFingerprintTest`, `OrderStatusTest`, publisher/outbox tests, `ServiceTokenProviderTest` (client-credentials caching), `OrderCancellationListenerTest`, `StuckSagaReaperTest` | `OrderApiIntegrationTest` (Postgres), `OrderKafkaIntegrationTest` (Postgres+Kafka), `OrderSagaIntegrationTest` (Postgres+Kafka — the whole Order→Inventory→Payment saga end to end over a real broker, with `MockRestServiceServer` standing in for the two downstream services so order-service stays independently testable), `ResilienceIntegrationTest` (no Testcontainers — a narrow Spring context slice, see [resilience.md](resilience.md)), `JwtResourceServerIntegrationTest` (no Testcontainers either — the platform-wide token-acceptance contract, see below), `OrderCancellationCompensationIntegrationTest` and `StuckSagaReaperIntegrationTest` (Postgres+Kafka — cancellation compensation retried off the event, and the reaper's claim/lease/abandon behaviour including two instances racing; see [ADR 008](adr/008-reliable-compensation-and-stuck-saga-reaper.md)), `OutboxEventRepositoryIntegrationTest` (Postgres, a `@DataJpaTest` slice — the table's own contract: database-assigned `sequence_no`, the oldest-per-aggregate claim query, the retention delete), `OutboxConcurrencyIntegrationTest` and `OutboxCleanupIntegrationTest` (Postgres+Kafka — two publishers racing one table, a failed send holding back its aggregate's stream, backoff, and concurrent retention runs; see [ADR 006](adr/006-outbox-concurrency-and-ordering.md)) |
| payment-service | `MockPaymentProviderTest`, `PaymentServiceTest`, publisher/outbox tests | `PaymentApiIntegrationTest` (Postgres) |
| delivery-service | `DeliveryServiceTest`, `ShipmentServiceTest`, `DeliveryAgentServiceTest`, publisher/outbox/listener tests | `DeliveryApiIntegrationTest` (Postgres) |
| notification-service | `NotificationEventListenerTest` | `NotificationEventListenerIntegrationTest` (Kafka only — no database, see [service-boundaries.md](service-boundaries.md)) |
| api-gateway | — (no business logic to unit-test; see [architecture.md](architecture.md)) | `ApiGatewayRoutingIntegrationTest` |

Every service except api-gateway also carries an `ArchitectureTest` (Phase 18, see
[below](#architecture-tests-phase-18)); api-gateway is excluded because it has no layers
to enforce.

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

Phase 18 added two more cases to it, after the smoke test found that warehouse management
had never been reachable through the gateway (see
[below](#two-more-pre-existing-bugs-found-in-phase-18)): that *both* `/api/v1/inventory/**`
and `/api/v1/warehouses/**` reach inventory-service, and that `/.well-known/jwks.json`
reaches user-service. Routing bugs are invisible to a service's own tests by
construction — the service works fine; nobody can get to it — so the gateway's test is
the only place they can be caught.

Because it needs no Postgres/Kafka container, this is one of only two integration
tests in the whole platform (`ResilienceIntegrationTest` is the other) that actually
runs to completion in this sandbox — see the Docker caveat below.

## Where the outbox's container-backed tests live (Phase 15)

`OutboxEvent`, `OutboxEventRepository`, `OutboxPublisher`, `OutboxCleanupJob`,
`OutboxProperties`, and `OutboxMetrics` are byte-for-byte identical in order-,
inventory-, payment-, and delivery-service apart from their package declaration
([ADR 006](adr/006-outbox-concurrency-and-ordering.md)). All four carry the full unit
test suite (`OutboxPublisherTest`, `OutboxEventTest`, `OutboxCleanupJobTest`); only
order-service carries the three container-backed ones.

That is deliberate. Running four copies of "two publishers race one table" costs four
Postgres and Kafka containers per CI run to prove the same code four times. What *is*
service-specific — that each service's new Flyway migration applies cleanly to its own
existing schema, and that Hibernate's `ddl-auto: validate` accepts the mapping against
it — is already exercised per service, because every service's existing
`*ApiIntegrationTest` runs the whole migration chain against a real Postgres at startup
(and, as of Phase 16, all of those have actually been run — see the caveat section).

## The token-acceptance contract (Phase 16)

`JwtResourceServerIntegrationTest` is the test that would have caught the vulnerability
Phase 16 closed ([ADR 007](adr/007-asymmetric-jwt-signing.md)). It presents, against a
real Spring Security filter chain built from order-service's actual `SecurityConfig`:

- a token signed with the **old shared HMAC secret**, claiming ADMIN — which every
  service would have *accepted* before this phase, and any service could have produced;
- an `alg: none` token;
- a token correctly signed by a real RSA key that is not in the JWKS;
- expired, wrong-issuer, wrong-audience, and post-signature-tampered tokens;

and requires a `401` with the unchanged error body for every one of them. It also pins
down what must still work: `roles` becoming `ROLE_*` authorities, and
`authentication.getName()` being the user id every ownership check compares against.

Like `ResilienceIntegrationTest`, it is a narrow slice rather than a full
`@SpringBootTest` — only the beans the contract depends on, plus an embedded stub JWKS
endpoint — so it needs no Postgres and no Kafka and **runs everywhere**, including here.
Each service's own `*ApiIntegrationTest` additionally carries the old-HMAC-token check
against a real endpoint of its own, because "rejected by every service" is a claim worth
making per service.

## Five pre-existing failures found in Phase 16

Worth recording, because the honest version of "the tests pass" has to include when they
did not.

CI had been **red on `main` since before Phase 15**, failing in `user-service` — the
second module in the reactor — which meant every module after it was `SKIPPED` and had
not actually run in CI for a long time. Five real failures were hiding behind that. All
five predate Phase 16 and are unrelated to it (each was reproduced on the commit before
it, and the saga one on the commit before Phase 15 as well); all five were fixed there,
because the build could not otherwise be green:

1. **`UserApiIntegrationTest.adminToken()`** loaded the ADMIN `Role` in one transaction
   and saved a new `User` referencing it in another, leaving the role detached.
   `User.roles` cascades `PERSIST`, so saving the user cascaded a persist onto a detached
   entity and Hibernate refused. This was the failure stopping the reactor.
2. **product-service's Redis cache** serialized values with a Jackson mapper that had no
   `JavaTimeModule`, so writing any `ProductResponse` (it carries `Instant` timestamps)
   threw and turned every cache read-through into a `500`.
3. **order-service returned 500 on reading an order.** `Order.items` is lazy and
   `open-in-view` is (correctly) false, so mapping an order to an `OrderResponse` in the
   controller — outside the transaction — raised `LazyInitializationException`. It
   affected `GET /orders/{id}`, `GET /orders/user/{userId}`, and an idempotent replay of
   `POST /orders`. Fixed with an `@EntityGraph` on the three repository reads.
4. **Dead-lettered messages went to a topic nobody was watching.** Both consumer configs
   relied on `DeadLetterPublishingRecoverer`'s default destination, which is
   `<topic>-dlt` — while the Javadoc, docs/kafka-events.md, and the tests all said
   `<topic>.DLT`. The suffix is now named explicitly.
5. **The saga never advanced past `CREATED`.** `OrderSagaOrchestrator.startSaga` called
   its own `@Transactional` `markReservationPending`/`markPaymentPending` methods. Spring's
   `@Transactional` is proxy-based, so a self-invocation applies no transaction at all:
   the order was loaded in the repository's own transaction, mutated after that
   transaction had closed, and never written back. Every subsequent event then found the
   order in an unexpected state and skipped itself as "already handled", so **no order
   ever reached `PAID`**. The two methods moved to `OrderSagaEventHandler`, where they are
   called across beans like every other transition and the proxy applies.

Numbers 3 and 5 in particular were real production bugs, not test bugs: the platform's
headline flow did not work. They are fixed and `OrderSagaIntegrationTest` — which
exercises Order → Inventory → Payment end to end over a real broker — now passes for the
first time.

## Architecture tests (Phase 18)

Six of the services get the same four rules, enforced by ArchUnit against the compiled
classes:

| Rule | What it protects |
|---|---|
| `controllersDoNotTouchRepositoriesDirectly` | Transaction boundaries and business rules live in the service layer; a controller reaching past it is how they stop being applied. |
| `domainDoesNotDependOnTheWebLayer` | Dependencies point inwards. A DTO or a `@RestController` changing must not be able to ripple into the domain model. |
| `businessCodeDoesNotPublishToKafkaDirectly` | The outbox ([ADR 004](adr/004-outbox-pattern.md)) only works if *nothing* writes to Kafka outside it. A service class holding a `KafkaTemplate` is precisely the dual-write the outbox exists to eliminate. |
| `onlyTheEventAndConfigPackagesTouchKafka` | The same rule from the other direction, stated as an allowlist so a new package can't quietly acquire a `KafkaTemplate`. `config` is allowed because `KafkaConsumerConfig` has to hand one to `DeadLetterPublishingRecoverer`. |

**notification-service gets different rules on purpose.** It has no web, domain,
repository, or service packages at all — it is a pure consumer
([docs/service-boundaries.md](service-boundaries.md)) — so three of the four rules matched
zero classes and ArchUnit failed them with "failed to check any classes". The tempting fix
is `allowEmptyShould(true)`, but that would turn an empty match into a silent pass for the
other six services too, which is exactly the failure mode these tests exist to catch. So
notification-service asserts its own invariants instead: it exposes no HTTP API, owns no
database, publishes no events of its own, and its listeners don't know how a notification
is physically delivered.

## Two more pre-existing bugs found in Phase 18

Both were found by *running* the platform rather than by reading it, which is the point of
the smoke test. Neither was caused by Phase 18, and neither could have been caught by any
test that existed before it.

6. **None of the eight jars were executable.** The build imports the Spring Boot BOM
   rather than inheriting from `spring-boot-starter-parent`, and `spring-boot-maven-plugin`
   was declared without a `repackage` execution — which the parent POM would have supplied
   and a BOM import does not. So `mvn package` produced plain library jars with no
   `Main-Class`, and every one of the eight Docker images would have died at startup with
   `no main manifest attribute, in /app/app.jar`. CI had never noticed because
   `docker-build` only ever *built* images; nothing had ever started one. Fixed by adding
   the `repackage` execution to the root `pluginManagement`; all eight manifests now carry
   `Main-Class: org.springframework.boot.loader.launch.JarLauncher`.
7. **`/api/v1/warehouses/**` was never routed through the gateway.** inventory-service's
   route predicate matched `/api/v1/inventory/**` only, so warehouse management — the API
   you need before you can stock anything — was unreachable through the front door and
   only worked if you bypassed the gateway and called port 8084 directly. Fixed in
   `api-gateway/src/main/resources/application.yml`, with two new cases in
   `ApiGatewayRoutingIntegrationTest` covering it and the JWKS route.

Bug 6 is the more serious of the two: the platform's published container images could not
have run at all. It is also a good illustration of why the smoke test is worth its
complexity — a test suite that never starts the artifact it ships cannot tell you the
artifact doesn't start.

## Docker / Testcontainers caveat

This platform was built in a sandbox with no Docker available (confirmed repeatedly
across every phase via proxy 403s reaching Docker Hub). For most of its history that
meant every `*IntegrationTest` needing Postgres and/or Kafka was verified to *compile*
here and left to CI to actually run.

**Phase 16 closed that gap.** Testcontainers still cannot start, but its dependencies
can be run directly: PostgreSQL 16, Redis 7.0, and a single-node Kafka 3.9 broker (KRaft)
were installed and started in the sandbox, and **every** `*IntegrationTest` in the
platform was executed against them — one class at a time, each against a freshly
formatted broker and a freshly created database, by pointing a throwaway copy of the
class at `localhost` instead of at a container. All of them passed (twelve classes at the time; two more were added in Phase 17 and run the same way):

| Test class | Tests |
|---|---|
| `UserApiIntegrationTest` | 17 |
| `ProductApiIntegrationTest` | 11 |
| `InventoryApiIntegrationTest` | 15 |
| `PaymentApiIntegrationTest` | 8 |
| `DeliveryApiIntegrationTest` | 4 |
| `OrderApiIntegrationTest` | 12 |
| `OrderSagaIntegrationTest` | 3 |
| `OrderCancellationCompensationIntegrationTest` | 3 |
| `StuckSagaReaperIntegrationTest` | 5 |
| `OrderKafkaIntegrationTest` | 3 |
| `OutboxConcurrencyIntegrationTest` | 3 |
| `OutboxCleanupIntegrationTest` | 3 |
| `OutboxEventRepositoryIntegrationTest` | 6 |
| `NotificationEventListenerIntegrationTest` | 3 |

Those throwaway copies were scaffolding and are not committed; the committed tests are
the Testcontainers ones, unchanged. Two differences from CI remain and are worth stating:
the sandbox ran **PostgreSQL 16 and Kafka 3.9** where the committed tests pin
`postgres:17-alpine` and `confluentinc/cp-kafka:7.7.1`, and nothing here has exercised
Testcontainers' own container lifecycle. Everything the tests actually assert — schema,
migrations, SQL, locking, consumer groups, retries, dead-lettering, the saga end to end —
has now genuinely run.

This is also how the five pre-existing failures above were found. They had been invisible
precisely *because* these tests only ran in CI, where the reactor stopped at the second
module.

If you're picking this repository up locally with Docker available, running
`mvn clean verify` (the full reactor, no `-Dtest` filter) is the first thing worth doing
to confirm that caveat doesn't hide a real problem.
