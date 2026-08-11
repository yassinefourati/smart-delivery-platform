# Resilience

> Implemented in Phase 11, and only in `order-service`: it's the one service in this
> platform that makes synchronous REST calls to another service (product-service,
> inventory-service, payment-service -- see
> [service-boundaries.md](service-boundaries.md#communication-matrix)). No other
> service calls another service synchronously, so no other service needs this.

## Why Resilience4j, and what it's protecting against

A synchronous call to a dependency can fail in ways a bare `RestClient` call doesn't
handle on its own: the dependency is slow (ties up a thread waiting), the dependency is
down (every call fails the same way, over and over, for as long as it stays down), or
the dependency is being hammered by everyone calling it at once. `ProductServiceClient`,
`InventoryServiceClient`, and `PaymentServiceClient` each carry four Resilience4j
annotations, one instance per downstream service so every operation against that
service (`reserve`/`release`/`deduct` for inventory-service; `charge`/`refund` for
payment-service) shares one breaker/bulkhead/limiter, not three independent ones:

| Annotation | What it does here |
|---|---|
| `@CircuitBreaker` | After enough calls to a downstream fail, stop calling it for a while (`wait-duration-in-open-state`) and fail immediately instead -- protects order-service from spending its own threads waiting on a downstream that's already down, and protects the downstream from more load while it's unhealthy. |
| `@Retry` | A single transient failure (a dropped connection, one slow response) gets a couple of automatic retries with exponential backoff before it's treated as a real failure. |
| `@Bulkhead` | Caps concurrent in-flight calls to one downstream (`max-concurrent-calls: 20`) so a slow dependency can't exhaust order-service's own thread pool by piling up calls to just that one downstream. |
| `@RateLimiter` | Caps how fast order-service can call a downstream (`limit-for-period` per `limit-refresh-period`) -- protects the downstream from a burst, independent of whether it's currently healthy. |

`spring.http.client.connect-timeout`/`read-timeout` (2s/3s) bound how long a single call
can hang in the first place -- without this, a stuck TCP connection could block far
longer than any of the above settings would suggest, since none of them can act on a
call that hasn't failed yet.

## Business failures are not infrastructure failures

The one design decision that actually matters here, and the one thing
`ResilienceIntegrationTest` verifies by actually running (see
[below](#what-actually-got-verified)): a `409 Conflict` from inventory-service
(insufficient stock) is a normal, valid business outcome, not a sign that
inventory-service is unhealthy. `InventoryServiceClient.reserve` translates it to
`InsufficientStockException`, which `application.yml` explicitly lists in both
the `inventory-service` circuit breaker's and retry's `ignore-exceptions` --

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventory-service:
        ignore-exceptions:
          - com.smartdelivery.order.exception.InsufficientStockException
  retry:
    instances:
      inventory-service:
        ignore-exceptions:
          - com.smartdelivery.order.exception.InsufficientStockException
```

Getting this wrong in either direction breaks something real: if `InsufficientStockException`
counted toward the circuit breaker, one warehouse legitimately running out of one
product could trip the breaker for every other order trying to reserve anything from
inventory-service, healthy or not. If it counted toward retry, order-service would
retry a decision that was never going to change on retry -- wasted calls, not a
correctness bug (the retry itself is idempotent, per [saga.md](saga.md)), but wrong
all the same. `PaymentServiceClient.charge` has no equivalent exception to exempt: a
decline isn't an HTTP error there at all (see that class's Javadoc) -- payment-service
always returns `201` with the outcome in the body, so there's nothing for Resilience4j
to see either way.

## No fallback methods, on purpose

None of the six annotated methods declare a `fallbackMethod`. This is deliberate, not
an oversight, and the reason is different for the two call sites:

- **The saga's calls** (`InventoryServiceClient`/`PaymentServiceClient`, from
  `OrderSagaOrchestrator`) run inside a `@KafkaListener`
  (`OrderSagaStartListener`). A fallback that swallowed a `CallNotPermittedException`
  (circuit open), `BulkheadFullException`, or `RequestNotPermitted` and returned some
  placeholder result would silently break the saga's resumability story
  ([saga.md#resumability](saga.md#resumability)): letting the exception propagate
  instead means Spring Kafka's existing retry + dead-letter handling
  (`KafkaConsumerConfig`, Phase 6) picks it up and retries the whole step later, exactly
  like any other infrastructure failure already documented there. No new machinery
  needed -- Resilience4j's fail-fast exceptions are just one more kind of
  infrastructure failure the saga was already built to tolerate.
- **product-service's call** (`ProductServiceClient`, from `OrderService.create`) is on
  the synchronous order-creation request path -- there's no Kafka retry safety net to
  fall back on here, so an open circuit has to become an honest HTTP response instead
  of a fallback that pretends the call succeeded. `GlobalExceptionHandler` maps
  `CallNotPermittedException`/`BulkheadFullException`/`RequestNotPermitted` to `503
  Service Unavailable`, the same status `ProductServiceUnavailableException` already
  used for a direct connectivity failure -- both mean the same thing to the client:
  "this didn't happen because of you, try again."

## What actually got verified

Every other Testcontainers-based integration test in this codebase is disclosed as
compiled-but-unexecuted in this sandbox (Docker Hub pulls are blocked by this session's
egress policy). `ResilienceIntegrationTest` is the one exception: it needs no database
and no real Kafka broker, only a narrow Spring context built from the three REST client
beans, Resilience4j's own autoconfiguration, and Spring AOP -- so it actually runs, and
actually passed:

1. Six consecutive `409` (insufficient stock) responses all reach
   `InventoryServiceClient.reserve` as real HTTP calls and all become
   `InsufficientStockException` -- the circuit never opens, proven by every one of the
   six `MockRestServiceServer` expectations being satisfied
   (`mockRestServiceServer.verify()`), not just by not seeing an exception.
2. Four consecutive `500` responses from `release` open the circuit (a fast, small
   `sliding-window-size: 4` / `minimum-number-of-calls: 4` configured just for this
   test, not `application.yml`'s production sizing); the fifth call fails immediately
   with `CallNotPermittedException` and never reaches `MockRestServiceServer` at all.

## A version note, disclosed rather than glossed over

`io.github.resilience4j:resilience4j-spring-boot3`'s nominal latest release (2.4.0,
verified against Maven Central directly, not assumed) pulls in `resilience4j-spring6`
pinned at an older `2.2.0` -- a version-inconsistent classpath that fails at Spring
context startup with `NoClassDefFoundError` on `RxJava3FallbackDecorator`, a reactive
fallback-support class this project has no use for (order-service is a plain blocking
servlet application, no RxJava/Reactor anywhere). Same failure at `2.3.0`. Pinning
`resilience4j.version` to a fully self-consistent `2.2.0` (every resilience4j artifact
at the same version, confirmed via `mvn dependency:tree`) resolves it --
confirmed by `ResilienceIntegrationTest` actually starting the affected Spring context
and passing, not by assumption.
