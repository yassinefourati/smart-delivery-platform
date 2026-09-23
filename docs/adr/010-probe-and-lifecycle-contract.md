# ADR 010: The probe and lifecycle contract, and what readiness deliberately ignores

## Status
Accepted

## Context

Until Phase 20 every service in this platform exposed exactly one health answer:
`/actuator/health`, the aggregate, with `show-details: when-authorized`. Everything that
wanted to know whether a service was working read that one endpoint —
`docker-compose.yml`'s healthcheck, `depends_on: condition: service_healthy` (which is how
the gateway waits for its backends), and `scripts/e2e-smoke.sh`. There was no probe
configuration, no graceful-shutdown configuration, and no JVM container flags anywhere.

That is the correct amount of machinery for Docker Compose, where each service runs as
exactly one instance and there is one notion of health. "Is the database wired up and
working" genuinely *is* the question `depends_on` wants answered before it starts the
gateway, and the aggregate answers it.

A kubelet asks three questions instead of one, on a schedule, and acts on each of them
differently: *should I kill this container*, *should traffic be sent to it*, and *has it
finished starting*. The common recipe points all three at `/actuator/health`. This ADR
exists because that recipe is wrong here in a way that can be calculated in advance, and
because the alternative involves one genuine trade-off that should not be presented as an
obvious call.

### What the aggregate actually contains

Verified against the jars on this classpath rather than assumed, because the reasoning
below depends on it. `spring-boot-actuator-autoconfigure` 3.5.13 ships health contributors
for Cassandra, Couchbase, DataSource, DiskSpace, Elasticsearch, Hazelcast, JMS, LDAP, Mail,
Mongo, Neo4j, Rabbit, Redis and SSL — and **none for Kafka**. Plain `spring-kafka`, with no
Spring Cloud Stream, contributes none either. So the aggregate today is:

| Service | Aggregate contributors |
|---|---|
| user, inventory, order*, payment, delivery | `db`, `diskSpace`, `ping`, `ssl` |
| product | the same, plus `redis` |
| order | the same, plus `circuitBreakers` (the only service setting `management.health.circuitbreakers.enabled: true`) |
| api-gateway | `diskSpace`, `ping`, `ssl` — no DataSource, no Redis, no Kafka, and Spring Cloud Gateway registers no indicator of its own |
| notification-service | `diskSpace`, `ping`, `ssl` |

The last row is worth sitting with. notification-service has no `spring-boot-starter-data-jpa`
and no Postgres driver, so nothing in its aggregate touches the only thing it does. It
reports UP while consuming nothing at all.

### What happens if the kubelet reads that

1. Postgres has a blip. `DataSourceHealthIndicator` fails on every replica of the six
   schema-owning services inside the same `periodSeconds` tick — not staggered, because
   each service's replicas share one database and one clock. `db: DOWN` makes the
   aggregate DOWN, and `SimpleHttpCodeStatusMapper` maps DOWN to **503**.
2. At a conventional `failureThreshold: 3` / `periodSeconds: 10`, that is thirty seconds of
   database unavailability before every pod of every service is killed at once.
3. The restart cannot recover. Flyway runs during context refresh and
   `spring.flyway.connect-retries` defaults to **0**, so Flyway throws on the first failed
   connection, the context fails, and the JVM exits non-zero. That is CrashLoopBackOff,
   with exponential backoff to a five-minute cap.
4. So a sixty-second blip becomes a ten-minute platform-wide outage, in which pods sit in
   restart backoff long after Postgres came back, with a full Kafka consumer-group
   rebalance storm, cold HikariCP pools and cold JWKS caches on the way out.

Nothing is corrupted by any of that. The outbox publisher's transaction rolls back and its
rows are ordinary `PENDING` rows again ([ADR 006](006-outbox-concurrency-and-ordering.md));
a saga interrupted mid-step is resumable and the reaper re-drives it
([ADR 008](008-reliable-compensation-and-stuck-saga-reaper.md)). **The availability damage
is caused entirely by the probe, not by the blip.** A dependency that a restart cannot
repair must not be able to trigger a restart.

One more discovery from reading the contributors, because it disqualifies the aggregate as
a machine-readable contract independently of any of the above. resilience4j 2.2.0's
`CircuitBreakersHealthIndicator` maps an OPEN breaker to the custom status `CIRCUIT_OPEN`
(with `allowHealthIndicatorToFail` defaulting to false). `SimpleStatusAggregator`'s
comparator orders statuses by `order.indexOf(code)`, which returns `-1` for a code it does
not know and therefore sorts it *ahead of* DOWN — while the HTTP status mapper, which has
no mapping for it either, still returns **200**. order-service's `/actuator/health` can
report a top-level status string nothing maps, at a status code that says everything is
fine. That is a fine thing for a human to read and a bad thing to make a control loop out
of.

## Decision

Split the one health answer into two probe groups, point the kubelet at those and never at
the aggregate, and write down the lifecycle contract the probe numbers are sized against.

### Two groups, set explicitly, with no shared dependency in either

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:
          include: livenessState
        readiness:
          include: readinessState
```

Identical in all eight services. Four things about that block are deliberate:

**`probes.enabled` is set rather than inferred.** `AvailabilityProbesAutoConfiguration`
turns these groups on by itself when it detects `CloudPlatform.KUBERNETES`. Leaning on
that would mean the probe endpoints exist in the cluster and not under `docker compose` or
`mvn spring-boot:run` — the one configuration nobody can test locally would be the only one
production uses.

**Only `livenessState` and `readinessState` are named.**
`management.endpoint.health.validate-group-membership` defaults to true, so naming a
contributor a service does not have fails the context at startup: `db` in
notification-service, `redis` anywhere but product-service. Restricting the groups to the
two availability states, which `probes.enabled` always registers, is what makes one
identical block safe to paste into all eight files.

**Liveness is near-constant, on purpose.** `LivenessState` starts at `BROKEN` and
`EventPublishingRunListener.started()` publishes `CORRECT`; nothing in this codebase ever
publishes `BROKEN` again. What is really under test is the machinery *around* the constant
— the port is open, a request thread is free, and the JVM completed a response inside
`timeoutSeconds`. Those are exactly the failures a restart fixes: a wedged thread pool, GC
thrash on the way to an OOM, a deadlock. If liveness should later catch an
application-level deadlock, the right change is to publish
`AvailabilityChangeEvent(LivenessState.BROKEN)` from the code that detects it, not to add a
dependency check here.

**Readiness excludes the shared dependencies.** This is the decision with a real cost and
it gets its own section.

### The shared-dependency question, argued both ways

**The case for putting `db` in readiness.** A pod that cannot reach its database cannot
serve. Take it out of the Service, let the load balancer send traffic to a replica that
can, and put the pod back when it recovers. This is the standard recipe, it is what most
readers will expect to find, and it is *right* whenever the failing dependency is per-pod.

**The case against, and why it wins here.** Every replica of order-service points at the
same `DB_URL`. The failure is therefore never per-pod: all replicas fail readiness in the
same tick, the EndpointSlice drops to zero endpoints, the Service stops resolving, and
callers get a connection-level failure with **no response body** — instead of the
503-with-a-correlation-id that `platform-starter`'s `ApiErrors` and
`PlatformExceptionHandler` exist to produce and that `CorrelationIdFilter` exists to make
traceable ([ADR 009](009-platform-starter-and-the-shared-code-boundary.md)). Through the
gateway it becomes a bare 503 after `connect-timeout`, with nothing to grep for. This
platform spent a whole phase on correlation; discarding it at the exact moment an incident
begins is a bad trade.

> **Checked against the running services, and the premise was false.** When Postgres was
> stopped underneath the live stack, every database-backed request came back as
> `500 INTERNAL_ERROR` with a full stack trace logged per request -- not the 503 this
> paragraph relies on. `PlatformExceptionHandler` had no mapping for an unreachable
> database, so it fell through to the catch-all. It now maps
> `CannotCreateTransactionException` and `DataAccessResourceFailureException` to
> `503 SERVICE_UNAVAILABLE` with the request's correlation id and a single WARN line, and
> the same test then returned 503 in about two seconds. The argument above is sound; it
> simply was not true of the code until the test was run. See
> [docs/kubernetes.md](../kubernetes.md#live-check-what-a-database-outage-does-to-the-probes).

Readiness-based removal only pays when *some* replicas are healthy and traffic has
somewhere to move. With a shared dependency there is nowhere, so it costs the diagnostics
and buys nothing. It also adds two failure modes likelier than the outage it guards
against:

- **Cold-start deadlock.** Bring a namespace up and Postgres may land after the services.
  A rollout that waits on readiness never completes.
- **Pool-saturation cascade.** `DataSourceHealthIndicator` runs `SELECT 1` per probe.
  Under saturation `getConnection()` blocks, the probe times out, and a pod that is merely
  *busy* is declared not ready, removed, and has its load shifted onto replicas that are
  also nearly saturated. This is not hypothetical: `platform-overview.json` has a HikariCP
  saturation panel precisely because the authors treat it as live.

**The failure mode this accepts, stated plainly rather than glossed.** A pod whose *own*
database connectivity is broken while its peers are fine — a per-node DNS fault, a
NetworkPolicy that applies to one node, a connection leak confined to one process — stays
in rotation and serves 503s for its share of traffic instead of being removed. That is a
real regression against the standard recipe. It is accepted because it is *visible* (the
per-service error rate on the Grafana dashboard, and correlation ids in the response
bodies) and because it is then a human decision, which is the right handling for a symptom
whose cause is almost always outside the pod. Someone reading this later and concluding the
opposite would not be making a mistake; they would be weighing a single degraded replica
against a whole-service diagnostic blackout differently, and that is a legitimate
disagreement.

`redis` in product-service is the same call for a weaker dependency:
[ADR 005](005-redis-caching.md) says Redis is disposable, a cold cache changes latency and
not correctness, so a Redis outage must never remove product-service from its Service. One
honest caveat that surfaced while checking it: **ADR 005's stated consequence is not
actually implemented.** `CacheConfig` registers no `CacheErrorHandler`, so a Redis outage
throws `RedisConnectionFailureException` out of the `@Cacheable` interceptor and 500s the
read rather than falling through to Postgres. Keeping `redis` out of readiness is still
right — a gate would trade 500s for zero endpoints — but the fix for a Redis outage is a
`CacheErrorHandler`, and that gap is recorded here rather than papered over by a probe.

`circuitBreakers` stays out twice over. Semantically an open breaker means a *downstream*
is unhealthy, and the whole point of [docs/resilience.md](../resilience.md) is that
order-service handles that correctly by failing fast — it is working, not broken.
Mechanically, see the `CIRCUIT_OPEN` aggregation bug above.

### What removing `db` from the groups does *not* fix

This is the most important line in this ADR for anyone who thinks the probe groups finished
the job. Nothing in this repository sets `management.server.port`, so **both probe groups
are served by the application's own Tomcat worker pool.** "Can this JVM answer at all" is
therefore not independent of the shared database; it is coupled to it through the request
threads. With HikariCP's default 30s `connection-timeout` and default pool of 10, a slow
database parks all 200 workers in `getConnection()`, the kubelet's probe is accepted at the
socket and never dispatched, it blows `timeoutSeconds`, and the platform-wide restart storm
arrives anyway by a different route — with `db` in neither group.

Two answers were available. The one taken is to make a saturated pod **shed** load instead
of accumulating threads: `spring.datasource.hikari.connection-timeout: 2000` and
`maximum-pool-size: 5` in the six schema owners. A request that cannot get a connection
fails fast into the platform's own error contract, the worker returns to the pool, and a
thread stays free to answer a probe. The pool size is not cosmetic either: six services at
the default of 10, at two replicas, is 120 connections against a stock Postgres
`max_connections` of 100, and the service that loses does not degrade — it fails to start.

The one **not** taken is `management.server.port`, which would give actuator its own
connector and its own threads and make the liveness rationale straightforwardly true. It is
deferred, not dismissed, for two reasons that are both about the rest of this phase: the
scrape design needs one port named `http` on all eight Services so that a single
ServiceMonitor covers them and the Prometheus Operator derives the `job` label from the
Service name; and the six `SecurityConfig` chains do not apply to Boot's management child
context, so everything in `management.endpoints.web.exposure.include` would become
reachable unauthenticated on a second port that nothing documents. The Helm chart supports
it as a uniform opt-in and leaves it off. **The 2s timeout is a mitigation, not the fix,**
and that distinction should survive into whoever revisits this.

### Startup is a separate probe, and it is mandatory

A `startupProbe` with `periodSeconds: 5` and `failureThreshold: 60` — 300 seconds — for the
seven servlet services, and 120 seconds for api-gateway, which has no schema to migrate.

It is required for two independent reasons before "migrations can be slow" is even reached.
Flyway runs during bean initialization, *before* `finishRefresh()` starts the web server, so
during a migration the port is not open and a probe gets connection-refused. And even after
the port opens there is a window in which `/actuator/health/liveness` returns 503, because
`ApplicationAvailability.getLivenessState()` is `BROKEN` until `ApplicationStartedEvent`.

Without a startupProbe the only lever is `livenessProbe.initialDelaySeconds`: one fixed
guess that must cover the *slowest* possible boot and then permanently blinds liveness for
that whole window on every restart. A startupProbe decouples the two — generous during
boot, tight afterwards, with liveness suppressed entirely until it first succeeds.

The 300s is a **migration** budget and its arithmetic is worth writing down, because
choosing it from JVM boot time would make it far too small: `FLYWAY_CONNECT_RETRIES` 10 × 5s
(50s of waiting for Postgres) + `FLYWAY_LOCK_RETRY_COUNT` 50 × 1s (50s of waiting for a
peer replica's migration) + roughly 30s of boot and context refresh (Compose's
`start_period` is 30s) + a 60s allowance for a migration that currently takes under a
second. The cost is bounded at both ends: a pod that will never start is still killed at
five minutes, and 300 is less than a Deployment's default `progressDeadlineSeconds` of 600,
so a genuinely broken rollout is still reported as failed.

What this probe prevents, concretely: liveness firing mid-migration, killing the pod,
Flyway restarting from scratch, and the loop repeating forever. On Postgres that usually
rolls back cleanly — transactional DDL, and Flyway wraps the migration and its
`flyway_schema_history` insert in one transaction, so an interrupted run leaves no failed
row and needs no `flyway repair`. The exception is precisely the migration someone would
write to avoid downtime: `CREATE INDEX CONCURRENTLY` cannot run inside a transaction, and an
interrupted run leaves an INVALID index plus a failed history row that does need a manual
repair.

### Shutdown is written down, not inherited

`server.shutdown: graceful` is pinned in all eight services even though **Boot 3.5 already
defaults it to `graceful`** — it was `immediate` before 3.5. Writing it changes no behaviour
today. It is written because every number below is sized against it, and a default that has
already flipped once is not something to size a shutdown budget against silently.

`spring.lifecycle.timeout-per-shutdown-phase` bounds **each** `SmartLifecycle` phase
independently; it is not a total. The phases, with the constants read off the 3.5.13 /
6.2.17 / 3.3.14 jars:

| Phase | Bean | Which services |
|---|---|---|
| `2147483547` | Kafka listener containers (`AbstractMessageListenerContainer`) | order, delivery, notification |
| `2147482623` | graceful request drain (`WebServerGracefulShutdownLifecycle`) | all eight |
| `2147481599` | web server stop (`WebServerStartStopLifecycle`) | all eight |
| `1073741823` | the `@Scheduled` pool (`ExecutorConfigurationSupport`) | inventory, order, payment, delivery |
| `-2147483648` | the shared Kafka producer (`DefaultKafkaProducerFactory`), closed with a 30s `DEFAULT_PHYSICAL_CLOSE_TIMEOUT` that Boot exposes no property to shrink | inventory, order, payment, delivery, notification |

Two entries are easy to get wrong in opposite directions, and both were got wrong in an
earlier draft of this work. The web-server stop is a **separate phase** from the request
drain, so even a service with nothing else in it has two phases and not one. And
`applicationTaskExecutor` is **not** on the list: Boot 3.5 annotates that bean `@Lazy`, so
nothing instantiates it in these services and it never joins the lifecycle. It would the
day one of them grows an `@Async` method.

Highest phase stops first, so the order is: consumers stop, HTTP drains, the connector
closes, the scheduler stops, the producer is closed last. **That ordering is already right
and nobody should "fix" it with an explicit phase** — the producer the outbox publishes
through is the last thing closed, after the poller that uses it has already stopped.

The numbers: 20s per phase for the seven servlet services, 15s for the WebFlux gateway, a
5s `preStop` sleep everywhere, and `terminationGracePeriodSeconds` of **45** (40 for the
gateway). Those three fields are one decision written in three places, and the Helm chart
fails its own render if they disagree.

20s is sized on the request drain, the only phase with legitimately long in-flight work: one
product-service lookup through Resilience4j `@Retry` at `max-attempts: 3` over
`spring.http.client`'s 2s connect and 3s read, plus 300ms and 600ms backoff and a 500ms
rate-limiter wait, is about 16s. Be precise about what that number is, because it is easy to
quote as a per-request bound and it is not: `OrderService` calls product-service once **per
line item** and `CreateOrderRequest.items` has `@NotEmpty @Valid` with no `@Size` cap, so
the per-request worst case is client-controlled and no drain budget can cover it. Two items
against a degraded product-service already exceed 20s. Capping that list, or giving the call
a request-level `@TimeLimiter`, is what would turn 20s into a real bound; it is a change to
the request contract rather than to configuration, and it has not been made.

**The preStop sleep is not a delay before shutdown becomes useful; it is a delay before
shutdown starts.** The kubelet runs `preStop` to completion and only then sends SIGTERM, so
five seconds of sleep is five seconds of serving *normally* after the pod has been told to
die. What it covers: on deletion the pod's EndpointSlice entry is marked
terminating/not-ready promptly — that, and not the readiness probe, is what stops new
traffic on modern Kubernetes — but every kube-proxy, CNI, ingress controller and mesh
sidecar still has to observe that update and reprogram iptables or ipvs, and that
propagation is asynchronous and unbounded. In that window kube-proxy routes to a pod whose
acceptor has already closed, and the client gets a TCP reset rather than a clean 503 —
which for a non-idempotent `POST /api/v1/orders` is not safely retryable.

The gateway differs for real reasons, not cosmetic ones: no Kafka containers and no
`@Scheduled` pool, so fewer phases; a proxied exchange is already bounded by its 3s connect
plus 5s response timeout, so 15s covers the longest thing in flight and carrying the
servlet services' 20s here would be cargo cult. Its readiness must also **never** depend on
a downstream — nothing on its classpath contributes such an indicator today, so that is a
guard against an obvious future change rather than a fix, but the failure it prevents is
cluster-shaped: a not-ready gateway means the Ingress has no backend, so a cold start
deadlocks (backends unready → gateway unready → nothing serves, including the routes whose
backends are fine) and a partial outage takes out the healthy routes with the broken one. A
gateway that stays up and returns a per-route 503 is strictly more useful than a gateway
with no endpoints.

### The change without which none of the above starts

`requestMatchers("/actuator/health")` matches that path and **nothing below it**. So an
unauthenticated `GET /actuator/health/liveness` falls through to
`anyRequest().authenticated()`, `JwtAuthenticationEntryPoint` returns 401, and the kubelet
reads any non-2xx as a failed probe. Shipping the groups without touching the six
`SecurityConfig` classes would put user-, product-, inventory-, order-, payment- and
delivery-service into permanent CrashLoopBackOff on a `failureThreshold` cadence, while
api-gateway and notification-service — no security starter, no filter chain — stayed up. A
CrashLoopBackOff that reads as a cluster fault and is a one-line authorization problem.

Both exact paths are listed, not `/actuator/health/**`: the two groups are all a kubelet
needs, and a wildcard would also hand every individual component path to an
unauthenticated caller. This is the same argument api-gateway's own routes already make by
writing `/v3/api-docs/<service>` out one by one instead of wildcarding.

## Consequences

**Nothing here has been applied to a real cluster.** Docker and Kubernetes are unavailable
in the environment this phase was built in. The YAML parses, every framework behaviour
relied on above was verified against the actual jars rather than recalled, and the chart's
rendered objects validate against the published Kubernetes schemas — but no kubelet has read
one of these probe stanzas, no pod has been asked to shut down inside one of these grace
periods, and the probe endpoints have not been curled. Every `periodSeconds`,
`failureThreshold` and grace period here is a reasoned starting point to be re-measured
against observed boot and shutdown times on the first real cluster. This is the same class
of gap [docs/observability.md](../observability.md) already records for the Grafana
dashboards, extended to Kubernetes.

**The aggregate did change, contrary to the tidy claim that this is purely additive.**
`probes.enabled: true` does not only add groups: `AvailabilityProbesAutoConfiguration`
registers `livenessStateHealthIndicator` and `readinessStateHealthIndicator` as beans, so
they join the **default** group too. `/actuator/health` therefore now returns 503 from the
moment the context starts closing, and between the port opening and `ApplicationReadyEvent`
— not only when `db` is down. There is no supported property to exclude a contributor from
the primary group. For Compose this is an improvement (`depends_on: service_healthy` now
waits for genuinely ready rather than merely connected), and nothing in the test suite or
`scripts/e2e-smoke.sh` asserts on the aggregate's contents. But anything that later alerts
on the aggregate will fire on ordinary shutdowns, and that is worth knowing before it does.

**Probes now detect strictly less than the naive recipe would, deliberately.** A service
that is up and completely broken — a saga wedged on a bug, a consumer that has silently
stopped — will never be restarted by Kubernetes. That detection moved to metrics on purpose:
`order.saga.outcomes`, `saga.stuck.count` and `outbox.oldest.pending.age.seconds` all
already exist for exactly this. **This phase is therefore only complete once something
alerts on those gauges**, and until then the platform is quieter than it was rather than
safer.

**A pod with a private database problem stays in rotation.** Restated here rather than left
in the Decision, because it is the cost that will actually be paid one day. See the
trade-off section for why it was chosen and why the opposite choice would also be
defensible.

**Shutdown's pathological tail is deliberately left to SIGKILL.** Summing every phase's
worst case plus the 30s producer close gives roughly 135s for order-service, and sizing the
grace period for that would mean every stuck pod holds a rollout slot for over two minutes.
45 covers the drain being used in full with everything else behaving. A SIGKILL past that
point is safe here — an interrupted outbox batch rolls back to PENDING (ADR 006) and a
claimed saga's lease expires so a peer picks it up (ADR 008) — so the number is about
avoiding metric and redelivery noise, not correctness. The way to make the tail cheaper is
to shrink its dominant term: a `ProducerFactory` customizer calling
`setPhysicalCloseTimeout(5)` in `platform-starter`, next to the outbox code that owns the
trade-off.

**Rolling deploys now produce slightly more duplicate Kafka deliveries.**
`spring.kafka.listener.immediate-stop: true` on order-, delivery- and notification-service
stops after the current record instead of draining up to `max.poll.records` of 500, each
with three retries available — an effectively unbounded wait inside a bounded phase. The
uncommitted remainder is redelivered, which is safe by design (`enable-auto-commit: false`
already means a hard kill redelivers, every saga step is idempotent, and at-least-once has
been a documented property since [ADR 002](002-kafka-for-events.md)), but it is a real
behaviour change: expect a few more duplicate-handling paths to be exercised, and the
occasional duplicate notification log line after a deploy.

**The 2s HikariCP timeout trades slow successes for a pod that stays answerable.** A request
that would have got a connection after four seconds now fails instead. That is the
intended direction — a diagnosable 503 with a correlation id beats a pod the kubelet
declares dead — but it is a change to behaviour under load, not only to behaviour during an
outage.

**A latent 401 was fixed on the way past.** `/actuator/prometheus` was in
`management.endpoints.web.exposure.include` and scraped by
`infrastructure/prometheus/prometheus.yml`, but was in no service's public list, so six of
the eight scrape targets had been returning 401 — in Compose, silently, since Phase 12. The
same exact-match blind spot as the probe paths. It is scope creep in a probe change and it
is recorded as such; the alternative was leaving a known-broken scrape in place for tidiness.

## Alternatives considered

**`/actuator/health` as the probe target — the recipe almost every tutorial gives.**
Rejected for the reasons in Context. It is kept exactly as it was for docker-compose, where
the aggregate genuinely is the right question, which is why this change is additive there
and cannot affect `scripts/e2e-smoke.sh`.

**`db`, `redis` or `circuitBreakers` in readiness.** The real alternative, argued at length
above rather than dismissed. It is right when the dependency is per-pod and wrong when it is
shared, and every dependency here is shared.

**`management.server.port` with its own connector.** The genuinely better fix for the
thread-pool coupling, and it would also make the six `SecurityConfig` edits unnecessary,
since a management child context has no filter chain. Deferred because it breaks the
single-named-`http`-port assumption the scrape design rests on, and because "no filter
chain" cuts both ways — every exposed endpoint becomes unauthenticated on a second port. The
chart carries it as a documented, uniform-only opt-in so that the escape hatch is not a
research task later.

**`management.endpoint.health.probes.add-additional-paths: true`** — the `/livez` and
`/readyz` aliases. They exist for the case where actuator is on a separate
`management.server.port`. Nothing sets one, so this would add two more public paths for no
benefit.

**Letting Spring Boot detect Kubernetes and enable the groups itself.** Rejected so that
local and deployed behaviour are identical; see the Decision.

**`spring.task.scheduling.shutdown.await-termination: true`.** Rejected after reading the
bytecode, and it is worth recording because it reads like a safer, longer wait and is the
exact opposite. In `ExecutorConfigurationSupport`,
`onApplicationEvent(ContextClosedEvent)` sets `lateShutdown = true` whenever
`waitForTasksToCompleteOnShutdown` is set and then **returns**, skipping `markShutdown()`
and `initiateEarlyShutdown()`, while `stop(Runnable)` tests the same flag and fires the
phase callback immediately. So setting it makes phase `1073741823` complete in microseconds,
stops pausing submission, and lets a fresh outbox poll start during the request drain and
run on into bean destruction — by which point the shared producer has been closed in phase
`-2147483648`. `OutboxPublisher.publishOne` catches `Exception` and calls
`recordPublishFailure`, and Micrometer counters are not transactional, so every rolling
deploy would put a batch-sized spike on `outbox.publish.failures`: the metric
[docs/observability.md](../observability.md) says to alert on, and precisely what that
property normally gets added to prevent. The default path is the one that behaves, and what
this phase contributes instead is `spring.task.scheduling.pool.size` (3 in order-service, 2
in the other three schedulers), because Boot's default of 1 means an outbox batch holding
the only thread is time in which the cleanup job and the stuck-saga reaper do not run *at
all*.

**`terminationGracePeriodSeconds` as the sum of every phase's worst case.** An earlier draft
had 125s for order-service. See Consequences: it sizes for a pod that is already stuck, and
SIGKILL there is safe.

**Any leader election or ShedLock for the `@Scheduled` jobs.** Not considered seriously, and
recorded so nobody re-opens it: ADR 006's `FOR UPDATE SKIP LOCKED` and ADR 008's
`updated_at` lease already make all four jobs safe with N replicas, and adding a
single-active mechanism on top would make every replica but one idle. That would be a
regression, not a hardening.

**`/actuator/health/**` in the security configuration instead of two exact paths.** One
character shorter and it publishes every individual component path to unauthenticated
callers.
