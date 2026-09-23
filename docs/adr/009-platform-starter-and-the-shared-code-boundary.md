# ADR 009: A platform starter, and where the shared-code boundary sits

## Status
Accepted

## Context

By Phase 18 this platform had eight services built to the same template, and the template
had been applied by copy-and-paste. The same infrastructure existed as byte-identical
files in service after service:

| Class | Copies | Divergence between copies |
|---|---|---|
| `CorrelationIdFilter` | 6 | none |
| `ErrorResponse` | 6 | none |
| `JwtAuthenticationConverterConfig` | 6 | none |
| `JwtAuthenticationEntryPoint` | 6 | a Javadoc comment in one |
| `JwtAccessDeniedHandler` | 6 | a Javadoc comment in one |
| `OutboxEvent`, `OutboxStatus`, `OutboxEventRepository`, `OutboxCleanupJob`, `OutboxProperties`, `OutboxMetrics` | 4 each | none |
| `OutboxPublisher` | 4 | one Javadoc sentence |
| `OpenApiConfig` | 6 | a title and a description string |
| `GlobalExceptionHandler` | 6 | four handlers identical in all six; the rest genuinely per service |

Deleting those copies removed about **3,800 lines of main source** from the eight service
modules, plus another 1,500 lines of duplicated tests, and trimmed a further 300 lines
from the `GlobalExceptionHandler`s that stayed.

The cost is not the disk space, and it is not really the typing either. It is that a fix
has to be applied *n* times and there is nothing that notices when it is applied fewer
than *n* times. This platform has already paid that bill twice. The dead-letter suffix bug
(Phase 16, `docs/testing.md`) was one wrong constant that had to be corrected in two
consumer configs; the outbox concurrency rewrite (Phase 15, [ADR 006](006-outbox-concurrency-and-ordering.md))
was a subtle change to a claim query that had to land identically in four services, and
ADR 006 says so in as many words — it recorded the duplication as "the main argument for
the shared platform starter Phase 19 weighs". A correctness-critical `FOR UPDATE SKIP
LOCKED` query maintained in four places by hand is a bug waiting for someone to be busy.

The question this ADR answers is therefore not *whether* to share, but **what**. The
failure mode of a shared module in a microservices codebase is well known and worse than
the duplication it replaces: a "common" library that accumulates domain types, so that a
change to one service's model forces a lockstep release of all of them, and the services
stop being independently deployable in everything but name.

## Decision

Introduce a single new Maven module, `platform-starter`, containing **Spring Boot
auto-configuration for infrastructure only**, and draw an explicit line around what may
live in it.

### What moves into `platform-starter`

Code that is (a) about the *mechanics* of being a service in this platform rather than
about any service's domain, and (b) something we actively want to be identical
everywhere — where divergence is a bug, not a design choice:

- **`CorrelationIdFilter`** — one request, one id, one header name, one MDC key.
- **The error contract** — `ApiErrors` (the body builder) and `PlatformExceptionHandler`
  (the four mappings every service had: validation, authentication, authorization, and
  the catch-all).
- **Resource-server wiring** — `JwtAuthenticationConverter` (claims to authorities and
  principal), `JwtAuthenticationEntryPoint`, `JwtAccessDeniedHandler`.
- **The transactional outbox** — all seven classes.
- **OpenAPI document metadata** — title, version, security scheme, and the server URL.

### What deliberately stays duplicated

- **Event payload classes and `EventEnvelope`.** Unchanged from
  [ADR 002](002-kafka-for-events.md): each service owns its own copy of every payload it
  publishes or consumes, and consumers tolerate unknown fields. This is the single most
  important line in this ADR. A shared `events` module is the most tempting thing to put
  in a starter and the one that would do real damage: it makes the wire format a
  compile-time dependency, so a producer cannot add a field without every consumer
  rebuilding against the new version, which is exactly the coupling asynchronous
  messaging exists to remove. Duplication here is not an oversight being tolerated; it is
  the mechanism.
- **`SecurityFilterChain`.** Every service keeps its own. What differs between them is
  precisely what matters in a filter chain — which endpoints are public, which need a
  role, which need ownership — and a shared chain parameterised by path patterns would
  move "who can call this" out of the service and into configuration assembled across two
  modules. The *wiring* is shared; the *policy* is not.
- **Domain exceptions and their mappings.** `OrderNotFoundException` and its 404 belong
  to order-service. Only the four universal mappings moved; the optimistic-locking and
  upstream-unavailable mappings stayed where they are, because their messages name the
  aggregate the caller was working on and a generic version would be worse than what is
  there.
- **Flyway migrations, including `outbox_events`'s DDL.** The Java moved; the schema did
  not. Each service owns its own database ([ADR 001](001-database-per-service.md)), and a
  shared module writing DDL into seven schemas would undo that. The table happens to be
  identical everywhere, and each service still declares it.
- **Anything in a service's `domain`, `service`, `web`, or `repository` package.** No
  exceptions, and this is the rule to enforce if the boundary is ever argued about.

### How it is wired

Auto-configuration, not a base class and not `@ComponentScan("com.smartdelivery.platform")`.
A service adds one dependency and gets the beans; it overrides any of them by declaring
its own (every bean is `@ConditionalOnMissingBean`); and it can switch the outbox off with
a property. Each auto-configuration is `@ConditionalOnClass`, and every dependency in the
starter's POM is `<optional>true</optional>`, so nothing is imposed on a service that has
a different stack: api-gateway is reactive and gets none of the servlet wiring;
notification-service has no database and gets no outbox.

One wiring detail is worth recording because the obvious approach is wrong.
`OutboxEvent` is a JPA entity and `OutboxEventRepository` a Spring Data repository, both
in a package outside the service's `@SpringBootApplication`. Annotating the
auto-configuration with `@EntityScan`/`@EnableJpaRepositories` would *replace* Boot's
auto-configured scanning rather than add to it — declaring `@EnableJpaRepositories`
anywhere makes `JpaRepositoriesAutoConfiguration` back off, and every service's own
repositories would silently stop being found. Instead the auto-configuration appends its
package to `AutoConfigurationPackages`, which both entity scanning and repository scanning
already read, leaving each service's packages exactly as they were.

## Consequences

**A bug in the outbox is now fixed once.** So is a change to the error body, the
correlation header, or how a token's claims become authorities. That is the entire point.

**`platform-starter` is a lockstep dependency, and that is the cost.** Every service that
depends on it rebuilds when it changes. This is acceptable *only* because of what is in
it: nothing here changes for a domain reason, so a change to it is an infrastructure
change that should reach every service anyway. The moment something domain-shaped lands
in this module, that reasoning stops holding — which is why the boundary above is written
down rather than left to taste.

**Two modules do not use it.** api-gateway (reactive, no servlet chain, its own
correlation filter) and notification-service (no web API, no database, no outbox) depend
on nothing from it. A shared module that two of eight modules ignore is a sign the
boundary is drawn in roughly the right place, not a sign it failed.

**The services got smaller.** Around 4,000 lines of copied infrastructure left the eight
service modules, and what remains in each `exception` package is that service's own
domain error mapping — which is what you actually want to read when you open it.

## Alternatives considered

**Leave it duplicated.** Defensible for two or three copies; not for six, and not for a
correctness-critical concurrent claim query maintained in four. ADR 006 already flagged
this as the case that had outgrown the argument.

**A shared library on the classpath with `@ComponentScan`.** Simpler to write and worse
to use: a service cannot opt out of a component scan, cannot override a bean without
excluding a package, and gets a `NoSuchBeanDefinitionException` at startup rather than a
condition that quietly does not apply. Auto-configuration exists for exactly this and is
what a Spring Boot developer will expect to find.

**A shared parent POM instead of a code module.** Solves dependency-version drift, which
the imported BOM already solves here, and shares no code at all. It is orthogonal, not an
alternative.

**Extract everything shared, including events.** Rejected — see "What deliberately stays
duplicated". This is the version of a shared module that turns a microservices platform
into a distributed monolith, and it is the reason this ADR spends more words on the
boundary than on the module.
