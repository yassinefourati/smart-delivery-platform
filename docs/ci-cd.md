# CI/CD

`.github/workflows/ci.yml` runs on every push to `main` and every pull request into it.

## Jobs

- **`build-and-test`** — `mvn -B clean verify` across the whole 8-module reactor: every
  unit test and every Testcontainers integration test (see
  [docs/testing.md](testing.md)) runs here, since GitHub Actions runners have Docker
  available (unlike the sandbox this platform was built in). Since Phase 18 the same
  command also runs the quality gates below — JaCoCo coverage, SpotBugs, and the ArchUnit
  rules — so there is one place a change has to pass, not a separate "lint" job that can
  drift from what a developer runs locally. Surefire reports *and* the JaCoCo/SpotBugs
  reports are uploaded as build artifacts on every run, pass or fail, so a failure's
  actual output is one click away instead of buried in the raw log.
- **`docker-build`** — builds each of the 8 services' Docker images from their
  `Dockerfile`s, matrix'd so one service's build failing doesn't block the others from
  reporting. Every push and PR gets a real build (proving the `Dockerfile` still
  produces a working image), cached via `type=gha` so a small code change doesn't
  re-resolve every Maven dependency from scratch. Since Phase 18 the image is built with
  `load: true` into the runner's local daemon as `local/<service>:ci` and **scanned with
  Trivy before anything else happens to it**. **Only a push to `main`** additionally
  logs in to GitHub Container Registry and publishes the image, tagged `:latest` and
  `:<commit-sha>` — a PR (including one from a fork, which wouldn't have registry
  credentials anyway) only ever proves the image builds and scans clean, never publishes
  one.
- **`e2e-smoke`** — runs `scripts/e2e-smoke.sh` against the real `docker-compose.yml`
  stack: `docker compose up -d --build`, wait for health, then drive a complete customer
  journey **through the gateway only**. See [the section below](#end-to-end-smoke-test).
- **`compose-config`** — `docker compose config --quiet`, validating `docker-compose.yml`
  parses and every variable substitution resolves, on every push and PR.

## Published images

`ghcr.io/yassinefourati/smart-delivery-platform/<service>:latest` (and
`:<commit-sha>` for a specific build) for each of the 8 services, published on every
push to `main`. No extra registry secret is needed — GHCR accepts the workflow's own
automatic `GITHUB_TOKEN`, scoped to `packages: write` at the job level only (every other
job, and every step in this job before the publish, only needs `contents: read`).

## Quality gates (Phase 18)

All four run inside `mvn -B clean verify`, so the build a developer runs locally is the
build CI runs.

### Line coverage (JaCoCo)

`jacoco:prepare-agent` at `initialize`, `jacoco:report` and `jacoco:check` at `verify`.
The gate is a single `BUNDLE`-scoped `LINE`/`COVEREDRATIO` rule per module, configured
once in the root `pom.xml` and parameterised per module by the
`jacoco.line.coverage.minimum` property.

**The thresholds are measured, not chosen.** Picking a round number like 80% would
either be slack enough to let real coverage rot underneath it, or tight enough to fail
a build for reasons unrelated to the change. Instead the full suite (unit *and*
container-backed integration tests) was run once, the resulting per-module line coverage
read off the JaCoCo report, and each module's floor set to that number rounded **down**
to the nearest whole percent:

| Module | Measured line coverage | Enforced floor |
|---|---|---|
| api-gateway | 87.50% | 0.87 |
| user-service | 92.74% | 0.92 |
| product-service | 91.16% | 0.91 |
| inventory-service | 91.86% | 0.91 |
| order-service | 90.65% | 0.90 |
| payment-service | 89.44% | 0.89 |
| delivery-service | 87.09% | 0.87 |
| notification-service | 96.81% | 0.96 |

So the gate's job is not "reach 80%" — it is **"do not go backwards"**. A change that
adds untested code fails the module it landed in; a change that adds tests raises the
headroom and nothing else. The floors are worth re-measuring and raising when a phase
meaningfully improves coverage; they should never be lowered to make a build pass.

One caveat worth stating plainly: these numbers include the Testcontainers tests, so
they can only be met on a machine with Docker. Running `mvn verify` with the container
tests excluded needs `-Djacoco.line.coverage.minimum=0`, or the gate will fail on
coverage that was never executed rather than on coverage that was never written.

### SpotBugs

`spotbugs:check` at `verify`, `effort=Max`, `threshold=Medium`, `includeTests=false`
(test code has different, deliberate trade-offs). Findings are either **fixed** or
**excluded as a category with a written justification** in `spotbugs-exclude.xml` — never
silenced per class, so a genuinely new instance of an excluded pattern still has to be
looked at, and never suppressed inline where the reasoning would be invisible to the next
reader.

The initial run produced 88 findings across three patterns:

- **`EI_EXPOSE_REP` / `EI_EXPOSE_REP2` (87)** — "may expose internal representation by
  storing/returning a mutable object". Every instance is either a Spring-injected
  collaborator held by a constructor (the container owns that object's lifecycle; copying
  it would break dependency injection outright), a JPA entity's mapped collection (which
  Hibernate must hand out by reference for dirty checking and lazy loading to work), or a
  record carrying a collection into a DTO. Defensive copying would break the frameworks
  this code is built on, so the pattern is excluded wholesale.
- **`CT_CONSTRUCTOR_THROW` (1)** — `JwtKeyProvider` throws from its constructor when the
  configured signing key is unreadable. That is exactly the desired behaviour: a service
  that cannot sign or verify tokens must fail to start, not start and mint garbage. The
  finding's real concern is finalizer attacks on a partially-constructed object, which
  requires a finalizer; none of these classes has one.
- **`NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (1)** — a real bug, fixed in code.
  `PaymentServiceClient.charge` dereferenced the `RestClient` response body without
  checking it for `null`. A 2xx with an empty body would have thrown an NPE inside the
  saga step, which the saga would then have read as a failed charge and compensated —
  releasing stock for an order that may well have been charged. It now throws a
  `RestClientException` instead, which routes into the existing retry and dead-letter
  path.

### ArchUnit

One `ArchitectureTest` per service, run as an ordinary JUnit test, enforcing the layering
the codebase already follows so it keeps following it:

- **controllers never touch repositories** — a controller reaching past the service layer
  is how transaction boundaries and business rules quietly stop being enforced;
- **domain classes never depend on web/DTO classes** — the dependency runs inwards, so a
  DTO change can't ripple into the domain model;
- **only the `event` package touches `KafkaTemplate`** — this is the rule that protects
  the outbox ([ADR 004](adr/004-outbox-pattern.md)): a service class publishing directly
  to Kafka is exactly the dual-write the outbox exists to prevent. The `config` package is
  exempt because `KafkaConsumerConfig` legitimately hands a `KafkaTemplate` to Spring's
  `DeadLetterPublishingRecoverer`.

notification-service gets a **different** set of rules rather than the shared four,
because it genuinely has no web, domain, repository, or service packages — it is a pure
consumer ([docs/service-boundaries.md](service-boundaries.md)). Applying the generic
rules there produced "failed to check any classes", which is a vacuous pass pretending to
be a real one. Rather than switching on `allowEmptyShould` (which would have weakened the
rules for the other six services too), it asserts its *actual* invariants: it exposes no
HTTP API, owns no database, publishes no events of its own, and its listeners don't know
how a notification is physically delivered.

### Trivy image scan

`aquasecurity/trivy-action` scans each locally-built image for OS and library
vulnerabilities, `ignore-unfixed: false`, failing the job on **CRITICAL**.

#### Why CRITICAL only

The scan runs on every push and PR, and it gates publishing. A gate that fires constantly
gets routed around — and base images accumulate HIGH findings continuously, most of them
in packages this platform never calls into, most with no fix available on the day they
appear. Failing on HIGH would mean a red `main` on most Mondays for reasons nobody in
this repository can act on, which trains everyone to ignore the scan. CRITICAL is the
severity where "stop and deal with this now" is actually the right response, so that is
where the build stops. `ignore-unfixed: false` is the deliberate counterweight: an
unfixable CRITICAL still fails, because "there's no patch yet" is information for a human
to act on (pin a different base image, rebuild, or accept it explicitly), not a reason
for the pipeline to stay quiet.

Raising this to HIGH is the natural next step for a platform with a real deployment
target and someone on the hook for triaging the queue.

## End-to-end smoke test

`scripts/e2e-smoke.sh` — brings up the real `docker-compose.yml` stack and drives a full
customer journey against it. The CI job (`e2e-smoke`) runs it on every push and PR, with
a 30-minute timeout, and uploads `target/e2e-logs/**` as an artifact **on failure as well
as success** — a smoke test whose failure you can't diagnose is worse than no smoke test,
so the error trap dumps `docker compose logs` and `docker compose ps` before tearing the
stack down.

The script is deliberately constrained in one way that matters: **every call goes through
the gateway on `localhost:8080`.** Nothing talks to a service's own port. That is the only
way the test can prove the thing it exists to prove — that the platform works the way a
real client would use it, routing predicates and all. (It is how Phase 18 found that
`/api/v1/warehouses/**` had never been routed through the gateway at all.)

What it asserts, in order:

1. every service reports healthy, and the gateway answers;
2. a customer can register and log in; the bootstrap admin can log in
   (see [below](#the-bootstrap-admin));
3. the JWKS endpoint is reachable through the gateway and publishes **public parameters
   only** — no `d`, `p`, `q`, `dp`, `dq`, `qi` ([ADR 007](adr/007-asymmetric-jwt-signing.md));
4. an admin can create a category, a product, a warehouse, and stock; a customer can
   create a shipping address;
5. **the happy path** — an order placed with an `Idempotency-Key` reaches `PAID`, which
   means inventory reserved, payment charged, and the saga ran to completion across four
   services and a real broker;
6. **idempotency** — replaying the same `Idempotency-Key` returns *the same order*, not a
   second one;
7. **compensation on failure** — an order for more stock than exists ends `FAILED`, and
   availability returns to exactly what it was with nothing left reserved;
8. **compensation on cancel** — a `PAID` order is cancelled and its payment ends
   `REFUNDED` ([ADR 008](adr/008-reliable-compensation-and-stuck-saga-reaper.md)).

Two details about the script are worth knowing before changing it:

- **Every name is suffixed with a per-run id** (`RUN_ID`), because categories, SKUs, and
  warehouse names carry unique constraints. Without it the script passes once and then
  returns `409` forever, which is a miserable thing to debug at 2am.
- **The cancellation step retries with a fresh order.** Cancelling is only legal up to
  `PAID`; once delivery-service has consumed `payment.completed` and created a shipment,
  the order has to go through the delivery workflow instead
  ([docs/order-flow.md](order-flow.md)). Shipment creation follows payment automatically
  and within a couple of seconds, so the script polls tightly for `PAID` and cancels
  immediately — and if the shipment wins the race anyway, it starts a fresh order rather
  than failing. The alternative (asserting on whatever state the order happened to reach)
  would be a test that passes for the wrong reason.

Useful switches when running it by hand: `SKIP_BUILD=1` reuses already-built images, and
`KEEP_STACK=1` leaves the stack up afterwards so you can poke at it.

### The bootstrap admin

Several of the steps above need an admin, and the platform had no way to create the
first one — every admin-creating endpoint requires an existing admin. Phase 18 adds
`BootstrapAdminInitializer` in user-service: an `ApplicationRunner` that creates one
admin account at startup, **gated on `bootstrap.admin.email` and
`bootstrap.admin.password` both being set**.

`application.yml` deliberately does not declare those properties, not even as empty
strings — `@ConditionalOnProperty` treats a declared-but-blank property as present, which
would turn the opt-in into an always-on. They are set as environment variables in
`docker-compose.yml` (with an obviously-local-only password) and nowhere else, so a
deployment that doesn't set them gets no seeded account at all.

## Dependency updates

`.github/dependabot.yml` — weekly PRs for the Maven reactor (one entry at the repo root
covers all 8 modules' `dependencyManagement`), the 8 services' Dockerfile base images
(one entry per Dockerfile, since Dependabot's Docker ecosystem doesn't follow images
transitively across files), and the GitHub Actions used in `ci.yml` itself.

## What's deliberately not here

**Deployment.** This pipeline builds, tests, and publishes images — it does not deploy
them anywhere. There is no Kubernetes manifest, Helm chart, or cloud environment
anywhere in this repository to deploy *to*; adding a "deploy" job would mean inventing
infrastructure this project doesn't have and pointing at a target that doesn't exist,
which is worse than not having the step. `docker-compose.yml` remains the actual
"run this platform" mechanism, for local development only (see
[docs/local-development.md](local-development.md)) — publishing images to GHCR is
the natural stopping point for a CD pipeline with no real deployment target, and the
foundation a real one would build on.

**A separate lint/static-analysis job.** The quality gates run inside `mvn verify`
rather than as their own workflow job, deliberately: a gate that only exists in CI is a
gate developers discover after pushing. Everything CI enforces, `mvn -B clean verify`
enforces on a laptop, with the same configuration and the same thresholds.

**Trivy on anything but CRITICAL.** See [the rationale below](#why-critical-only).
