# Observability

## Today (Phase 1)

Every service already exposes, via Spring Boot Actuator:

- `GET /actuator/health` — used by Docker Compose health checks and, once deployed,
  container orchestrator liveness/readiness probes.
- `GET /actuator/info` — build/service metadata.
- `GET /actuator/metrics` — JVM, HTTP, and system metrics in Actuator's own format.

## Planned (Phase 12)

Deferred until there is real business logic worth observing — instrumenting a health
check endpoint is not a meaningful demonstration of observability. When Phase 12 lands,
this document will be expanded with the actual configuration; the design is fixed here
so later phases build toward one target:

- **Correlation IDs.** The API Gateway generates a `correlationId` (or forwards one
  supplied by the client via an `X-Correlation-Id` header, if present) and propagates
  it as an HTTP header to every downstream call and as the `correlationId` field on
  every Kafka event a request causes (see [kafka-events.md](kafka-events.md)). A
  `CorrelationIdFilter` in each service reads the incoming header, puts it in the SLF4J
  MDC so it's on every log line for that request, and re-attaches it to any outbound
  call or event the request triggers.
- **Structured logging.** JSON log output in non-local profiles, so correlation IDs and
  other fields are queryable rather than regex-scraped from plain text.
- **Metrics → Prometheus.** `micrometer-registry-prometheus` added to every service,
  exposing `/actuator/prometheus`; a `prometheus.yml` scrape config and a
  `docker-compose.yml` entry are added at the same time as the dependency, not before —
  a scrape target that scrapes nothing is noise.
- **Dashboards → Grafana**, provisioned (not clicked together by hand) for: request
  count, error rate, and latency per service; JVM memory/GC; DB connection pool
  saturation; Kafka consumer lag per topic/group; order processing failure rate (the
  one genuinely business-specific dashboard, since a healthy JVM with a broken saga is
  the failure mode that matters most here).
- **Distributed tracing → OpenTelemetry.** Spans across the gateway → service → Kafka →
  consumer chain, correlated by the same `correlationId` used in logs, so a slow order
  can be traced end-to-end rather than reconstructed from separate services' logs by
  hand.

## Why this is a separate phase instead of built in from Phase 1

Wiring Prometheus/Grafana against services that only expose a health check would mean
every dashboard in this repository was built and screenshotted against fake data, which
is worse than not having it yet — it hides what real instrumentation of real order/
payment/inventory flows will actually need to show. Section 20 of the engineering brief
still applies in full; this phase is deferred, not dropped.
