# Kubernetes

**Nothing in this repository has ever been applied to a Kubernetes cluster.** There is
no cluster to apply it to, and this platform was built in an environment with no
container runtime. What exists is a Helm chart that renders, lints and validates against
the published Kubernetes API schemas, plus the application-side changes a cluster needs
and a local stack does not. Treat the first real apply as the test.

`docker-compose.yml` is still how this platform runs day to day
([local-development.md](local-development.md)). Everything below was written so that the
move to a cluster is a deployment decision rather than a debugging session.

## Where each part is explained

| Topic | Read |
|---|---|
| Why liveness and readiness check what they check, the shared-dependency trade-off, startup and shutdown | [ADR 010](adr/010-probe-and-lifecycle-contract.md) |
| Installing the chart, values, secrets and key rotation, migrations, observability, validation guards, what was verified | [`deploy/helm/smart-delivery-platform/README.md`](../deploy/helm/smart-delivery-platform/README.md) |
| The application-side settings the chart depends on, and why each exists | this document |

The rule this document enforces: **each service's `application.yml` and `Dockerfile`
carry the setting and a one-line pointer here, not the argument.** Nine copies of a
paragraph drift, and the reason for a number belongs in exactly one place.

## The bug that would have stopped every pod

`requestMatchers("/actuator/health")` in Spring Security matches that exact path and
**nothing below it**. So the kubelet's unauthenticated `GET /actuator/health/liveness`
fell through to `anyRequest().authenticated()`, got a `401`, and a kubelet reads any
non-2xx as a failed probe. Shipped as-is, every replica of all six services that have a
`SecurityConfig` would have restarted forever -- while api-gateway and
notification-service, which have no security filter chain, stayed up. That reads as a
cluster fault. It is a one-line authorization problem.

Each `SecurityConfig` now lists the two probe paths **exactly**, not
`/actuator/health/**`: a wildcard would also hand every individual component path
(`db`, `diskSpace`, `redis`, `circuitBreakers`) to an unauthenticated caller.

**A pre-existing bug found alongside it: Prometheus has been getting `401` from six of
the eight services since Phase 12.** `/actuator/prometheus` is exposed by
`management.endpoints.web.exposure.include`, and `infrastructure/prometheus/prometheus.yml`
scrapes it on every service, but it was in no public list -- the same exact-match blind
spot. The Grafana panels for those six services could never have shown data, which is
consistent with [observability.md](observability.md)'s own note that the dashboards were
never exercised against a running stack. It is now public, exposing no more than the
`/actuator/metrics` entry already beside it.

## Application settings

All of these are env-var driven with the value shown as the default, so the chart --
or any environment -- overrides them without a rebuild.

### Probes and shutdown -- every service

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: ${SHUTDOWN_PHASE_TIMEOUT:20s}   # 15s on api-gateway
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        liveness:  { include: livenessState }
        readiness: { include: readinessState }
```

No shared dependency is in either probe group. `/actuator/health` is unchanged and still
aggregates `db`, Kafka and Redis, so `docker compose` and anyone checking by hand see
exactly what they did before. Why readiness deliberately ignores Postgres -- and which
failure that choice accepts -- is the whole of [ADR 010](adr/010-probe-and-lifecycle-contract.md).

`server.shutdown: graceful` is already Spring Boot 3.5's default. It is stated anyway,
because the grace-period arithmetic below depends on it and a default is not a contract.

### The shutdown budget

`timeout-per-shutdown-phase` bounds **each** `SmartLifecycle` phase independently -- it
is not a total. There are always at least two phases that can take real time (the
graceful request drain, and the web-server stop), so:

```
terminationGracePeriodSeconds >= preStop + 2 x timeout-per-shutdown-phase
                             45 >= 5       + 2 x 20                          (services)
                             40 >= 5       + 2 x 15                          (api-gateway)
```

**The chart refuses to render if these disagree.** Below the floor, the kubelet SIGKILLs
a pod that was shutting down correctly, which is how a rolling deploy starts producing
duplicate Kafka deliveries and `outbox_publish_failures` spikes. The chart passes
`SHUTDOWN_PHASE_TIMEOUT` itself, so the grace period and the Spring setting move together.

The `preStop` sleep is not a delay before shutdown becomes useful. Endpoint removal
propagates asynchronously to every kube-proxy and ingress controller, and the kubelet
runs `preStop` to completion **before** SIGTERM -- so it is five seconds of serving
normally while the pod leaves the load balancers.

### Database connections -- the six schema-owning services

```yaml
spring:
  datasource:
    hikari:
      connection-timeout: ${DB_CONNECTION_TIMEOUT_MS:2000}   # default was 30000
      maximum-pool-size: ${DB_POOL_MAX:5}                     # default was 10
```

**Both HikariCP defaults are wrong for a pod, and both are behaviour changes.**

`connection-timeout` is how long a request thread parks waiting for a connection once the
pool is exhausted. The probes are served by **the same Tomcat request threads** -- there
is no separate `management.server.port` -- so under the 30-second default, a slow database
fills all 200 workers, the kubelet's probe is accepted at the socket and never dispatched,
it times out on every replica of every database-owning service at once, and the kubelet
restarts the fleet into a database that is still slow. **Keeping `db` out of the probe
groups does not prevent that restart storm on its own; this setting is the other half.**
Two seconds turns it into load shedding: the request fails fast as an RFC 7807 `503` with
a correlation id, the thread returns to the pool, and one is always free for a probe.
Capping `server.tomcat.threads.max` does not help -- fewer threads park for the same 30
seconds.

`maximum-pool-size` defaults to 10, and that default is really the platform's replica
ceiling: six services x 10 x 2 replicas is 120 connections, against Postgres's default
`max_connections` of 100. The sixth service's second replica fails at datasource
initialisation with a message about connections and nothing about replicas. Five keeps
two replicas of everything at 60, with room for `psql` and Flyway. **Raise it together
with `max_connections`, or put PgBouncer in front -- never on its own.**

**Phase 23: the budget depends on the topology** ([ADR 015](adr/015-capacity-no-remote-calls-in-transactions.md)).
That arithmetic assumes one PostgreSQL shared by all six services, which is Compose and
the chart's default `database.topology: shared`. Since Phase 22 production gives each
service its own CloudNativePG cluster (`deploy/helm/sdp-data`), so what has to fit is
each service's `maxReplicas × poolMax` against **its own** cluster's app-role limit, not
the sum across services. `values-production.yaml` therefore sets
`topology: per-service`, `poolMax: 10` and `maxConnectionsBudget: 120` (sdp-data's
`appConnectionLimit`). The worst case is product-service at 8 × 10 = 80. The chart's
NOTES do whichever sum applies on every install.

The old 4 was a real ceiling, not just a number. The load test in
[load-testing.md](load-testing.md) shows what a small pool does when anything holds a
connection for too long. Holding a connection for too long was also the bigger bug, and
it is fixed separately: no remote call runs inside a transaction any more.

`SdpDbPoolSaturated` fires when requests have waited for a connection for five minutes.

### Migrations at startup -- the six schema-owning services

```yaml
spring:
  flyway:
    connect-retries: ${FLYWAY_CONNECT_RETRIES:10}
    connect-retries-interval: ${FLYWAY_CONNECT_RETRIES_INTERVAL:5s}
    lock-retry-count: ${FLYWAY_LOCK_RETRY_COUNT:50}
```

Kubernetes has no `depends_on: condition: service_healthy`. A pod is scheduled the moment
a node has room, and Flyway's default is `connect-retries: 0` -- so a database five
seconds behind the pod is a failed context refresh, a non-zero exit, and
`CrashLoopBackOff` whose backoff keeps the replica down for minutes after Postgres is
back. The interval has to be set alongside the count: Boot's default interval is 120s,
which would make ten retries twenty minutes of a pod that looks like a slow boot.

`lock-retry-count: 50` is Flyway's own default, restated because it is a cliff. On
PostgreSQL, Flyway serialises replicas with `pg_try_advisory_xact_lock` before reading a
single script, so N replicas booting together is safe: one migrates, the rest wait. But
they wait at most 50 seconds, and on the 51st failure the waiting replicas fail to start
-- which no startup-probe threshold can fix. **A migration whose worst case exceeds about
50 seconds needs this raised first, and is really the signal to stop migrating at
startup** (the chart README's "Migrations" section says what to move to and why the
images cannot do it yet).

### Scheduler threads -- order, inventory, payment, delivery

```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:2}   # 3 on order-service
```

**A latent bug this fixes, independent of Kubernetes.** The scheduler defaults to **one**
thread for every `@Scheduled` method in the service. The outbox publisher holds its
transaction across a whole batch of sends -- up to `outbox.batch-size` x the 5-second
per-send timeout -- and on a single thread that is time in which the cleanup job and, on
order-service, the **stuck-saga reaper do not run at all**. That reads as "the reaper is
not running", and is really "it never got a thread". More replicas do not fix it; each
one has the same single thread. The pool is sized to the number of scheduled jobs.

### Kafka consumers -- order, delivery, notification

```yaml
spring:
  kafka:
    listener:
      immediate-stop: true
```

On shutdown, stop after the record currently being handled rather than draining
everything the last poll returned -- up to 500 records, each with three retries
available, which is an unbounded-in-practice wait inside a bounded shutdown phase. The
uncommitted remainder is redelivered to whichever consumer takes the partition next.
That is safe here -- auto-commit is off, every saga step is idempotent
([saga.md](saga.md)), and at-least-once delivery has been this platform's contract since
[ADR 002](adr/002-kafka-for-events.md) -- **but it is a real behaviour change: expect more
duplicate-handling paths to be exercised after each rolling deploy.**

It does not bound a sick broker: the consumer's close and its partition-revocation
callback still run inside the same phase.

```yaml
spring:
  kafka:
    listener:
      concurrency: ${KAFKA_LISTENER_CONCURRENCY:1}   # Phase 23; production: 3
```

The number of consumer threads per `@KafkaListener` in this pod. It only helps up to the
topic's partition count, summed across pods. Production runs 2 order-service pods × 3
threads against 6 partitions, which the producers now create themselves
(`kafka.topics.*`, [kafka-events.md](kafka-events.md#partitions-and-consumer-concurrency)).
Each thread takes a database connection only for the short transaction a record needs.
Nine listeners × 3 threads is still well inside a pool of 10, because a thread holds a
connection only while it writes, never while it waits on the network.

### Gateway connection pool -- api-gateway

```yaml
spring.cloud.gateway.server.webflux.httpclient.pool:
  max-idle-time: ${GATEWAY_POOL_MAX_IDLE_TIME:8s}
  max-life-time: ${GATEWAY_POOL_MAX_LIFE_TIME:60s}
  eviction-interval: ${GATEWAY_POOL_EVICTION_INTERVAL:2s}
```

The other half of graceful shutdown, and the half the usual account of `preStop` misses.
`preStop` and endpoint propagation protect a client about to open a **new** connection.
A pooled keep-alive connection is already established and pinned to one pod, so no
endpoint bookkeeping touches it: when that pod closes, the gateway can still lease the
socket, write a request onto it, and get a `PrematureCloseException` -- which Reactor
Netty will not retry once bytes are on the wire. A `POST /api/v1/orders` then becomes a
`500` whose outcome the caller cannot determine. The `preStop` sleep makes this *more*
likely per deploy, not less, by keeping the connection healthy for five extra seconds.

Reactor Netty's defaults leave idle and lifetime unbounded, so the gateway held a pooled
connection for as long as the backend pod lived. `max-idle-time: 8s` sits well inside
Tomcat's 20-second keep-alive, so the gateway always discards an idle connection before
the backend closes it underneath; `eviction-interval` does that in the background rather
than on the next lease. `max-life-time` is what lets traffic actually reach a replica added
by a rollout or scale-out -- without it, established connections keep serving the old
pods and a new one sits idle looking healthy.

This narrows the race rather than closing it. The remaining answer is the caller's, and
it already exists: `POST /api/v1/orders` takes an `Idempotency-Key`, so a client that
sends one can retry a reset safely.

## The images

### A numeric user, or no pod starts

```dockerfile
RUN addgroup -g 1000 -S spring && adduser -u 1000 -S spring -G spring
USER 1000:1000
```

`runAsNonRoot: true` makes the kubelet verify the image's user before starting it, and it
can only do that when `USER` is **numeric** -- it does not read `/etc/passwd` inside the
image. With `USER spring:spring` every pod fails with "image has non-numeric user
(spring), cannot verify user is non-root". Pinning 1000 also stops the number moving when
the base image changes what `adduser -S` allocates, so the Deployment's `runAsUser` can
name it without inspecting the image.

### The jar is no longer duplicated in every image

The previous `RUN chown spring:spring app.jar` is gone. Changing one bit of metadata on a
fat jar rewrites the whole file into a new layer, **so every published image carried a
second copy of its jar -- 96MB for order-service**. The jar is now root-owned and
read-only, which is also the point of pairing the image with
`readOnlyRootFilesystem: true`: a process that cannot rewrite its own code.

### JVM flags

```dockerfile
ENV JAVA_OPTS="-XX:InitialRAMPercentage=60.0 -XX:MaxRAMPercentage=60.0 \
               -XX:MaxMetaspaceSize=256m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS $JAVA_OPTS_APPEND -jar /app/app.jar"]
```

| Service | Heap % | Memory limit | CPU request | Scheduler threads |
|---|---|---|---|---|
| api-gateway | 45 | 1Gi | 500m | -- |
| user-service | 60 | 768Mi | 750m | -- |
| product-service | 55 | 1Gi | 500m | -- |
| inventory-service | 60 | 768Mi | 400m | 2 |
| order-service | 60 | 1Gi | 750m | 3 |
| payment-service | 60 | 768Mi | 300m | 2 |
| delivery-service | 60 | 768Mi | 300m | 2 |
| notification-service | 55 | 512Mi | 200m | -- |

- **A percentage, not `-Xmx`.** Java 21 reads the cgroup memory limit, so one image sizes
  itself for whatever limit the pod is given, and the number that decides the heap lives
  beside `resources.limits.memory` where an environment can change it. The corollary: with
  **no memory limit**, the JVM sizes its heap for the whole node and the kernel kills it.
  A memory limit is the JVM's only sizing input.
- **Not 75%, the number every tutorial uses.** Non-heap cost -- metaspace, code cache,
  thread stacks, GC metadata -- is roughly fixed at around 300MB for a Spring Boot service
  of this shape. 75% is right for a 4Gi container and OOM-kills a 768Mi one. Each figure
  above is the limit minus that floor, not a round number.
- **`MaxMetaspaceSize` for diagnosability.** Uncapped, a class-loader leak grows past the
  container limit and dies with exit 137 and no Java output at all. Capped, it throws
  `OutOfMemoryError: Metaspace` and says so in the log.
- **`ExitOnOutOfMemoryError`.** A JVM that has thrown OOME is in undefined state, and here
  a half-dead instance is worse than a dead one: it can still hold outbox row locks and its
  Kafka group membership, keeping work away from healthy replicas while doing none of it.
- **G1 named explicitly**, because Java 21 silently switches to SerialGC below two CPUs or
  about 1.8GB -- so shrinking a pod would otherwise change its latency profile by accident.
- **The `exec` is load-bearing.** Without it the shell stays PID 1 and does not forward
  SIGTERM, so graceful shutdown never runs and every pod deletion waits out its grace period
  and is SIGKILLed. The array form of `ENTRYPOINT` cannot expand `$JAVA_OPTS` at all.
- **`JAVA_OPTS` replaces the set; `JAVA_OPTS_APPEND` adds to it and wins on any flag it
  repeats**, so an environment retunes through Deployment `env:` without a new image.
  `JAVA_TOOL_OPTIONS` was rejected: the JVM echoes it to stderr on every start, which is a
  non-JSON line in an ECS JSON log stream.

`-XX:ActiveProcessorCount` is deliberately **not** in the image. It must agree with the
CPU the pod is given, so it lives in the Deployment next to `resources.requests.cpu`. It
does have to be set: with no CPU limit there is no cgroup quota for the JVM to read, and
since JDK 19 it ignores `cpu.shares` -- so it would size GC and JIT threads from the node's
core count, 64 on a large node for a pod given half a core.

## What was verified

| Check | Result |
|---|---|
| `helm lint`, default and production values | pass |
| `helm template`, default values | 25 objects |
| `helm template`, production values | 41 objects: 8 Deployments, 8 Services, 9 ConfigMaps, 3 HPAs, 8 PDBs, Ingress, 2 NetworkPolicies, ServiceMonitor, PrometheusRule |
| `kubeconform -strict`, everything enabled | 52 objects: **50 valid, 0 invalid**; the 2 exceptions are the Prometheus Operator CRDs, which have no published schema |
| Rendered probe paths, startup probe, `preStop`, UID, read-only root filesystem, per Deployment | all eight correct; no Deployment for `platform-starter` |
| Validation guards | fired on purpose -- an unsafe grace period, and user-service scaled with no shared signing key -- both refuse to render with an actionable message |
| `mvn -B clean verify` and the container-backed test sweep | see the Phase 20 entry in the README |
| The probe endpoints and the kill-Postgres behaviour, against the running services | verified live -- see the last section |

helm and kubeconform could not be downloaded in this environment. Both were built from
source through the Go module proxy (`go install helm.sh/helm/v3/cmd/helm@v3.16.3`), which
is worth knowing if the same restriction applies to you. A `go install`-built helm reports a
simulated cluster of v1.20 because it carries no version ldflags, so `helm template` needs
`--kube-version` to satisfy the chart's `>= 1.25` constraint.

**Not verified, and it cannot be claimed:** no kubelet has read these probes, no pod has
shut down inside one of these grace periods, no ServiceMonitor has scraped anything, and
nothing was checked against an admission controller or a PodSecurity standard. The chart
README's "What has actually been verified" lists every gap.

## Live check: what a database outage does to the probes

The decision ADR 010 turns on is that **liveness must not restart a pod because a shared
dependency is down**. That is checkable against the running services without a cluster:
stop Postgres underneath them and read the endpoints.

All eight services were started as local JVM processes against Postgres, Kafka and
Redis, and every probe was read **unauthenticated**, as a kubelet reads it.

**With everything up:** liveness and readiness return `200 UP` on all eight, and
`/actuator/prometheus` returns `200` on all eight. `/actuator/health/db` still returns
`401` on the six secured services -- the probe paths are listed exactly, so component
paths stay protected. That `401` is the same exact-match behaviour that kept Prometheus
out since Phase 12.

**Then Postgres was stopped underneath the running services:**

| | liveness | readiness | `/actuator/health` |
|---|---|---|---|
| user, product, inventory, order, payment, delivery | **UP** | UP | DOWN |
| notification-service, api-gateway (no database) | UP | UP | UP |

Liveness stays up everywhere, so no kubelet restarts anything. Under the recipe this
replaces -- liveness pointed at `/actuator/health` -- all six database-owning services
would have read DOWN at the same moment and been restarted together, into a database that
was still down. The aggregate still reports DOWN, so a human or a dashboard sees the real
problem.

A database-backed request through the gateway during the outage:

```
GET /api/v1/categories -> 503 in 2.09s   error=SERVICE_UNAVAILABLE
GET /api/v1/categories -> 503 in 2.02s   error=SERVICE_UNAVAILABLE
GET /api/v1/categories -> 503 in 2.02s   error=SERVICE_UNAVAILABLE
```

Two seconds, not thirty, because of the Hikari `connection-timeout` above -- which is what
keeps request threads free to answer the probes. The log shows **one WARN line carrying the
same correlation id as the response**, and no stack trace.

**This test found a bug, and the numbers above are after the fix.** The first run returned
`500 INTERNAL_ERROR` for every request, with a full stack trace logged at ERROR each time
(191 stack-trace lines within seconds). A 500 tells a client "this is a bug, do not retry";
an unreachable database is the textbook 503. `PlatformExceptionHandler` now maps
`CannotCreateTransactionException` and `DataAccessResourceFailureException` to
`503 SERVICE_UNAVAILABLE`, logged as one line. It deliberately does **not** map all of
`DataAccessException` -- a constraint violation is a real bug and stays a 500 -- and a test
pins both sides.

**Then Postgres was started again.** The same request returned `200` within about a
second, from **the same process, which was never restarted**, and the aggregate health went
back to UP. That is the behaviour the probe design exists to produce: a shared outage is
survived, not amplified.

A service that is **started** while its database is down does not come up -- Flyway retries
for about 40 seconds with backoff, then exits. That is correct: it cannot migrate, so it
must not serve. In a cluster the startup probe holds it for its 300-second budget, then the
kubelet restarts it until Postgres returns.

### What this did not cover

Redis. product-service's `CacheConfig` has no `CacheErrorHandler`, so a Redis outage fails
product reads instead of falling through to Postgres as [ADR 005](adr/005-redis-caching.md)
intends. Redis is deliberately outside the probe groups too, so the probes behave correctly
-- but those requests will error rather than degrade. Found during this phase; recorded
here rather than fixed, because it is a caching-behaviour change to one service, not
Kubernetes readiness.

