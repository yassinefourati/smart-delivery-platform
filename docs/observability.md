# Observability

## Foundation (Phase 1)

Every service already exposes, via Spring Boot Actuator:

- `GET /actuator/health` — used by Docker Compose health checks and, once deployed,
  container orchestrator liveness/readiness probes.
- `GET /actuator/info` — build/service metadata.
- `GET /actuator/metrics` — JVM, HTTP, and system metrics in Actuator's own format.

## Correlation IDs (Phase 12)

`CorrelationIdGlobalFilter` in api-gateway generates a `correlationId` (or forwards one
already supplied by the client via an `X-Correlation-Id` header) and re-attaches it to
every proxied backend call and to the response, so a client that didn't send one can
still find it afterward. Each backend service's own `CorrelationIdFilter` reads that
header, puts it in the SLF4J MDC for the duration of the request, and re-attaches it to
any outbound REST call (`ProductServiceClient`/`InventoryServiceClient`/
`PaymentServiceClient` in order-service) or Kafka event the request triggers — every
`EventEnvelope.correlationId` published now carries the request's actual id, read back
out of the MDC, instead of a fresh random one per publish. Kafka listeners that react to
those events (`OrderSagaEventListener`, `OrderSagaStartListener`,
`PaymentCompletedListener`, `NotificationEventListener`) put the envelope's
correlation id back into the MDC for the duration of their handling, so consumer-side
logs carry it too.

Each backend `CorrelationIdFilter` runs at `Ordered.HIGHEST_PRECEDENCE + 2` — one step
after Spring Boot's own `ServerHttpObservationFilter` (registered at
`HIGHEST_PRECEDENCE + 1`), so the request's tracing span already exists by the time the
filter tags it (see Tracing below), and still well ahead of Spring Security, so
correlation covers every response including a 401/403.

**WebFlux caveat.** `CorrelationIdGlobalFilter` (api-gateway) does not put the
correlation id in the SLF4J MDC or tag the current span the way every backend service's
filter does. WebFlux doesn't run one request on one dedicated thread, so a plain
`MDC.put()`/`Tracer.currentSpan()` call there wouldn't reliably still be visible by the
time a later log statement or tag for the same request runs on a different Reactor
thread. Instead it logs the method/path/correlationId directly, in one line, at the
point the id is assigned or forwarded.

## Structured logging (Phase 12)

Every service's console log line includes the MDC correlation id via
`logging.pattern.level: "%5p [%X{correlationId:-}]"` (all backend services; api-gateway
omits this for the reason above, relying on its own explicit log line instead).

In the `docker` Spring profile (`SPRING_PROFILES_ACTIVE=docker`, set in
`docker-compose.yml`), every service switches to structured JSON console output via
Spring Boot's built-in `logging.structured.format.console: ecs` — no extra dependency
needed, just `spring-boot-starter-logging`. `mvn spring-boot:run` and IDE runs stay on
the plain-text pattern above, since the `docker` profile is never active there.

## Metrics → Prometheus (Phase 12)

`micrometer-registry-prometheus` is on every service, exposing `/actuator/prometheus`
(added to each service's `management.endpoints.web.exposure.include`).
`infrastructure/prometheus/prometheus.yml` scrapes all 8 services by their Docker
Compose container name; a `prometheus` service in `docker-compose.yml` runs it,
persisting data to the `prometheus-data` volume, reachable at `localhost:9090`.

`management.metrics.distribution.percentiles-histogram.http.server.requests: true` is
set on every service so Prometheus gets the `_bucket` series `histogram_quantile` needs
for the latency dashboard panel below — without it, Micrometer only exports the count
and sum for `http.server.requests`, not enough to compute a percentile in Prometheus
itself.

**Business metric.** `order.saga.outcomes` (a `Counter` tagged `outcome=delivered|
failed|cancelled`) is incremented in order-service each time an order reaches a
terminal state: `OrderSagaEventHandler` for saga-driven outcomes (delivery completing,
inventory reservation failing, payment failing), `OrderService#cancel` for
user-initiated cancellation. It backs the "order processing failure rate" dashboard
panel — the one genuinely business-specific panel, since a healthy JVM with a broken
saga is the failure mode that matters most here.

## Dashboards → Grafana (Phase 12)

A `grafana` service in `docker-compose.yml` (reachable at `localhost:3000`, anonymous
viewer access enabled for local dev) is provisioned — not clicked together by hand —
from `infrastructure/grafana/provisioning`:

- **Datasources** (`provisioning/datasources/datasources.yml`): Prometheus (default) and
  Tempo, both pointed at their Compose service names.
- **Dashboards** (`provisioning/dashboards/dashboards.yml` +
  `infrastructure/grafana/dashboards/platform-overview.json`): request rate, error rate,
  and p95 latency per service; order processing outcomes and failure rate; JVM heap used
  and GC pause time per service; HikariCP connection pool saturation (the 6 services with
  a database — api-gateway and notification-service have no pool and report no series on
  that panel).

**Scoped out.** A Kafka consumer lag panel is not included. It would need a
`kafka-exporter` (or Prometheus's own experimental Kafka support) added as another
Compose service and scrape target purely to feed one panel — a reasonable follow-up, not
included here since nothing else in this stack depends on it.

**Verification caveat.** Docker is unavailable in the sandbox this phase was built in
(confirmed via repeated Docker Hub proxy 403s in earlier phases), so `docker compose up`
was never actually run here. `docker compose config --quiet` validates the full compose
file's syntax and interpolation, `mvn compile`/`test` confirm every service builds and
starts, and the Prometheus/Tempo/Grafana config files were checked for valid YAML/JSON
syntax — but the dashboards' PromQL queries, the Prometheus scrape targets actually
succeeding, and the OTLP export actually reaching Tempo have not been exercised against
a running stack. Metric names used in the dashboard (`http_server_requests_seconds_*`,
`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_sum`, `hikaricp_connections_*`) are
Micrometer/Spring Boot's standard, documented metric names, not custom instrumentation,
so this is a low-risk gap — but it is a real one, and running `docker compose up` to
confirm dashboards actually render is worth doing before relying on this in anger.

## Distributed tracing → OpenTelemetry (Phase 12)

Every service has `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp`,
exports spans via OTLP/HTTP to Grafana Tempo (`management.otlp.tracing.endpoint`,
defaulting to `http://localhost:4318/v1/traces` locally and overridden to
`http://tempo:4318/v1/traces` in `docker-compose.yml`), and samples every request
(`management.tracing.sampling.probability: 1.0` — appropriate for a demo/dev platform
with no production traffic volume to worry about; the first thing to turn down if this
config were ever reused for a real deployment). `spring.application.name` (already set
per-service) becomes each span's `service.name` resource attribute automatically.

Each backend service's `CorrelationIdFilter` tags the current request's span with
`correlationId` (via `Tracer.currentSpan().tag(...)`, resolved through an
`ObjectProvider<Tracer>` so the filter doesn't require tracing to be present), so a trace
in Tempo and the corresponding log lines can be cross-referenced by the same id.
api-gateway's `CorrelationIdGlobalFilter` does not do this — see the WebFlux caveat
above; the correlation id and the OTel trace id both still end up in every backend
service's logs regardless, so the two remain cross-referenceable without it.

Tempo runs as its own `tempo` service in `docker-compose.yml` (single-binary/monolithic
mode, config at `infrastructure/tempo/tempo.yaml`), storing traces to the `tempo-data`
volume, reachable at `localhost:3200` (query API) and `localhost:4318` (OTLP/HTTP
ingest).

## Why this was a separate phase instead of built in from Phase 1

Wiring Prometheus/Grafana against services that only expose a health check would mean
every dashboard in this repository was built and screenshotted against fake data, which
is worse than not having it yet — it hides what real instrumentation of real order/
payment/inventory flows will actually need to show. Section 20 of the engineering brief
still applies in full; this phase was deferred, not dropped.
