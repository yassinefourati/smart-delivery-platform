# smart-delivery-platform (Helm chart)

Deploys the eight Smart Delivery Platform services -- `api-gateway` plus seven
backends -- into one namespace, as **one chart with a per-service values map**
rather than eight subcharts. Optionally deploys the React SPA alongside them.

> **This chart has never been applied to a real Kubernetes cluster.** See
> [What has actually been verified](#what-has-actually-been-verified) before you
> trust a number in it.

---

## Contents

- [Why one chart and not eight subcharts](#why-one-chart-and-not-eight-subcharts)
- [What this chart does NOT do](#what-this-chart-does-not-do)
- [Kubernetes version](#kubernetes-version)
- [Installing](#installing)
- [Prerequisites in the application config](#prerequisites-in-the-application-config)
- [How the gateway is exposed](#how-the-gateway-is-exposed)
- [Migrations](#migrations)
- [Secrets, and rotating the signing key](#secrets-and-rotating-the-signing-key)
- [Observability](#observability)
- [The shutdown budgets, and where the numbers come from](#the-shutdown-budgets-and-where-the-numbers-come-from)
- [The probes, and the one thing they do not decouple](#the-probes-and-the-one-thing-they-do-not-decouple)
- [The React frontend](#the-react-frontend)
- [Naming, and why it breaks Helm convention](#naming-and-why-it-breaks-helm-convention)
- [Validation guards](#validation-guards)
- [Known gaps this chart does not fix](#known-gaps-this-chart-does-not-fix)
- [What has actually been verified](#what-has-actually-been-verified)

---

## Why one chart and not eight subcharts

Because the services are deliberately uniform, and the uniformity is enforced
elsewhere in the repository rather than being a coincidence. All seven backends
build the same `eclipse-temurin:21-jre-alpine` image with the same numeric
`USER 1000:1000` and the same `ENTRYPOINT`; all eight get their correlation
filter, error contract, resource-server wiring and OpenAPI metadata from one
shared module, `platform-starter`
([ADR 009](../../../docs/adr/009-platform-starter-and-the-shared-code-boundary.md));
all eight expose the same actuator surface on a port that differs only in its last
digit.

Eight subcharts would be eight copies of that, and the first things to drift would
be the ones that hurt most -- the probe paths, the security context, the shutdown
budget. So the templates are shared and everything genuinely per-service is a
field in `values.yaml`:

| field | why it differs |
|---|---|
| `port` | 8080-8087, as the Dockerfiles `EXPOSE` them |
| `runtime` | `api-gateway` is WebFlux; the other seven are servlet |
| `ownsSchema` | six own a Postgres schema; `api-gateway` and `notification-service` do not |
| `hasOutbox` | `inventory`, `order`, `payment`, `delivery` publish through the transactional outbox |
| `hasKafkaListeners` | `order`, `delivery`, `notification` have `@KafkaListener` containers |
| `resources`, `activeProcessorCount` | reasoned per service from what it actually does |
| `terminationGracePeriodSeconds` | a sum over the shutdown phases that service has -- see [below](#the-shutdown-budgets-and-where-the-numbers-come-from) |
| `probes.startup.failureThreshold` | 300s where Flyway runs, 120s where it does not |
| `config`, `secretEnv` | that service's own knobs and credentials |

What is **not** per-service is anything where two copies would be a bug:
`JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_JWK_SET_URI`, the Kafka bootstrap, the OTLP
endpoint, the public gateway URL and the six `*_SERVICE_URI` values all live in one
`sdp-common` ConfigMap consumed with `envFrom`. `JWT_ISSUER` is minted by
`user-service` and validated as a claim by six resource servers -- one character of
drift between two copies is a 401 on every request with nothing in either log that
says "mismatch".

The six `*_SERVICE_URI` values and `JWT_JWK_SET_URI` are **generated** from the
Services the chart itself creates, so they cannot drift from the objects they name.
That answers the service-discovery question
[docs/architecture.md](../../../docs/architecture.md) left open, the way that
document predicted: "moving to Kubernetes Services, which give this for free."

---

## What this chart does NOT do

**It does not run Postgres, Kafka or Redis.** Those are expected to exist already,
as managed services (RDS/Cloud SQL/Azure Database, MSK/Confluent Cloud/Event Hubs,
ElastiCache/Memorystore) or as operator-managed clusters in the cluster
(CloudNativePG or Zalando for Postgres, Strimzi for Kafka).

The definitions of all three in `docker-compose.yml` are **for local development
only** and are not a starting point for a cluster: one Postgres instance with one
superuser shared across six databases, a single-broker Kafka in KRaft mode with no
replication and topics auto-created at one partition, and a Redis with no
persistence, no auth and no failover. Every one of those is the right shape for a
laptop and the wrong shape for anything with a user on it.

Two consequences that will bite on a first install:

1. **Somebody has to create six databases and six roles.** Flyway migrates
   *within* a database; it does not create the database or the role, and
   `infrastructure/postgres/init-databases.sh` is a Compose entrypoint hook that no
   cluster ever runs. Left undone, the first deploy fails at datasource
   initialization complaining about a database that does not exist. One role per
   service, `GRANT`ed on its own database only -- a shared role would let a
   compromised `product-service` pod read `order_db` directly, undoing at the SQL
   layer the boundary
   [ADR 001](../../../docs/adr/001-database-per-service.md) enforces in code.
   (Do **not** provision `notification_db`. `init-databases.sh` creates it, but
   `notification-service` has no `spring.datasource` at all and nothing has ever
   used it.)
2. **Kafka topics are not created here either.** No `NewTopic` bean exists in main
   code, so under Compose the broker auto-creates them at one partition. A managed
   broker usually has auto-creation disabled, and one partition is what caps
   `order-`, `delivery-` and `notification-service` at one effective consumer
   regardless of replica count.

**It does not install Prometheus, Grafana, Tempo or an OTel Collector.** It points
at them, and optionally registers a `ServiceMonitor` and a `PrometheusRule` with a
Prometheus Operator that already exists. The Grafana datasource definitions and the
dashboard JSON in `infrastructure/` stay shared with Compose -- the dashboard
hardcodes datasource `uid: prometheus` and `uid: tempo` in all 15 panels, so a
cluster Grafana has to provision those exact uids or every panel renders
"datasource not found".

**It does not run database migrations as a Job.** See [Migrations](#migrations).

**It does not create a ServiceAccount, Role or RoleBinding.** Nothing in this
platform calls the Kubernetes API, and every pod sets
`automountServiceAccountToken: false`.

**It does not manage TLS certificates.** `ingress.tls.secretName` names a Secret
that cert-manager or a human provides.

---

## Kubernetes version

`Chart.yaml` declares `kubeVersion: ">= 1.25.0-0"`, which is the floor for the
escape-hatch configuration rather than for the defaults -- Helm cannot express a
constraint that depends on values.

**This makes `--kube-version` mandatory for `helm template`.** With no cluster to
ask, Helm falls back to `chartutil.DefaultCapabilities`, which hardcodes *v1.20.0*,
and the constraint rejects it. `helm install` and `helm upgrade` query the real
server and need no flag. The alternative was to drop the constraint, and it is not
worth it: on a cluster that does not know `lifecycle.preStop.sleep`, the field can be
**pruned silently** rather than rejected, which removes the preStop sleep and brings
back the endpoint-propagation race with nothing to say it happened. A loud error at
install time is the point.

Validated with `kubeconform -strict` against the published API schemas:

| rendered with | 1.25 | 1.27 | 1.29 | 1.30+ |
|---|---|---|---|---|
| defaults | 8 Deployments rejected | rejected | accepted | accepted |
| `lifecycle.preStopUseNativeSleep=false`, `topologySpread.matchLabelKeys=[]` | accepted | accepted | accepted | accepted |

What each field needs:

- **`lifecycle.preStop.sleep`** -- the native sleep action, which needs no shell in
  the image. The schema accepts it from 1.29, but `PodLifecycleSleepAction` is
  *alpha* there and only beta-and-default-on from **1.30** (GA in 1.32), so treat
  1.30 as the real floor. Below that, `lifecycle.preStopUseNativeSleep=false`
  renders the `exec` form -- `eclipse-temurin:21-jre-alpine` has BusyBox `sleep` and
  it needs no privileges, so it works as UID 1000. It would stop working on a
  distroless base, which is why the native action is the default.
- **`topologySpreadConstraints.matchLabelKeys`** -- 1.27+ (beta), GA 1.30. An older
  API server **silently drops** it rather than rejecting it, which quietly brings
  back the rollout stall it exists to prevent, so set
  `topologySpread.matchLabelKeys=[]` there rather than leaving it to be ignored.
- **`livenessProbe.terminationGracePeriodSeconds`** / `startupProbe` -- 1.25 (GA).
  This is the 1.25 floor.

## Installing

```bash
# 1. the namespace, the databases, the roles, the topics and the Secrets first.
#    NOTES.txt prints the exact commands after a render.
kubectl create namespace smart-delivery

# 2. see what you are about to apply, with nothing applied.
#    --kube-version is NOT optional here: `helm template` has no cluster to ask, and
#    Helm's hardcoded DefaultCapabilities claim v1.20.0, which this chart's
#    kubeVersion constraint (>= 1.25.0-0) rejects. Pass the version you will actually
#    install onto. `helm install` asks the real server and needs no flag.
helm template sdp deploy/helm/smart-delivery-platform \
  --namespace smart-delivery --kube-version 1.31.0 | less

# 3. a single-replica install with every optional object off
helm install sdp deploy/helm/smart-delivery-platform \
  --namespace smart-delivery \
  --set image.tag=<commit-sha> \
  -f my-environment.yaml

# 4. or the production shape: two replicas, three HPAs, PDBs, Ingress,
#    ServiceMonitor, PrometheusRule, NetworkPolicy
helm upgrade --install sdp deploy/helm/smart-delivery-platform \
  --namespace smart-delivery \
  -f deploy/helm/smart-delivery-platform/values-production.yaml \
  -f my-environment.yaml
```

`values.yaml` is deliberately a **single-replica install with every optional object
switched off**: no HPA, no PDB, no topology constraints, no Ingress, no
ServiceMonitor, no PrometheusRule, no NetworkPolicy. The first thing an operator
does is get eight pods running, and each of those objects is a way for that to fail
for a reason unrelated to the platform -- a PDB on one replica that blocks every
node drain, a topology constraint that leaves pods Pending on a small cluster, a
CRD that is not installed.

`values-production.yaml` is the other half: read it as the second half of
`values.yaml` rather than as an example. Every `REPLACE_ME` in it has to come from
your own `-f` file, not from a file in this repository.

Before trusting a release:

```bash
helm get manifest sdp -n smart-delivery | grep REPLACE_ME   # must print nothing
```

### Values you will always have to set

| value | why |
|---|---|
| `image.tag` | `latest` is the default because that is what CI publishes, and it is wrong for anything real: two installs a week apart run different code under one name, and `helm rollback` rolls the chart back while leaving the image where it was. CI also publishes `:<commit-sha>`. |
| `common.kafkaBootstrapServers` | no default is possible |
| `common.publicGatewayUrl` | baked into every service's OpenAPI document; must be the public host |
| `services.<name>.config.DB_URL` | six of them, one per schema-owning service |
| `services.user-service.config.JWT_SIGNING_KID` | never ship `sdp-dev-key-1`; it is stamped into every token's header |
| `image.pullSecrets` | if the GHCR package is private |

---

## Prerequisites in the application config

Five things this chart depends on that live in the services' `application.yml`, and
that it cannot check for itself. All five were **verified present** when this chart
was written -- they ship in the same phase -- so this list is for the future where one
of them gets reverted or a service is added without them. Each entry is the symptom.

1. **The probe paths must be permitted to unauthenticated callers.** All three
   probes target `/actuator/health/liveness` and `/actuator/health/readiness`, and
   `requestMatchers("/actuator/health")` matches that path and **nothing below
   it**. Without both probe paths in `PUBLIC_ENDPOINTS`, the kubelet gets a 401,
   reads any non-2xx as a failed probe, and all six services with a
   `SecurityConfig` restart on a `failureThreshold` cadence forever -- while
   `api-gateway` and `notification-service`, which have no security starter and no
   filter chain, stay up. It reads as a cluster fault and it is a one-line
   authorization problem.
2. **`/actuator/prometheus` must be permitted too**, or six of the eight scrape
   targets return 401 and those panels are blank with nothing saying why.
3. **`management.endpoint.health.probes.enabled: true`**, with the two groups set
   to `liveness: livenessState` and `readiness: readinessState` and no shared
   dependency in either. Note the side effect: enabling probes also registers those
   two contributors in the **default** group, so the aggregate `/actuator/health`
   now returns 503 during shutdown and before `ApplicationReadyEvent`. Harmless
   here, but relevant if anything alerts on the aggregate.
4. **`FLYWAY_CONNECT_RETRIES`, `FLYWAY_CONNECT_RETRIES_INTERVAL` and
   `FLYWAY_LOCK_RETRY_COUNT` must be declared** as `${...}` placeholders. This
   chart sets all three (60 / 1s / -1, overriding the application defaults of
   10 / 5s / 50), but an env var only binds where the property references it.
   Without the placeholders they are silently ignored, and a pod scheduled seconds
   before Postgres is ready crashloops instead of waiting.
5. **`SHUTDOWN_PHASE_TIMEOUT` must be declared**, and `SCHEDULER_POOL_SIZE` on the
   four services with `@EnableScheduling`. The chart sets both, and
   `terminationGracePeriodSeconds` is validated against the first of them -- so if
   the placeholder disappears, the grace period stays consistent with a phase timeout
   the application is no longer using, which is the one failure in this list that
   would look like the chart's numbers being wrong rather than the config's.

Three other settings the chart sets as env vars that bind through Spring's relaxed
binding **whether or not** `application.yml` mentions them, which is why the chart
can fix them without a code change:

- `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` -- six services x pool 10 x two
  replicas is 120 connections against a stock `max_connections` of 100, and the
  service that loses does not degrade, it fails to start;
- `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` -- see
  [the probes](#the-probes-and-the-one-thing-they-do-not-decouple);
- `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_*` -- see
  [known gaps](#known-gaps-this-chart-does-not-fix).

---

## How the gateway is exposed

Every Service is `ClusterIP`, including `api-gateway`. Nothing is reachable from
outside the cluster until you pick one of:

1. **`ingress.enabled=true`** -- the recommended route. One host, TLS terminated at
   the controller, `/api/v1` and `/.well-known/jwks.json` to the gateway, `/` to
   the SPA when it is enabled.
2. **`services.api-gateway.service.type=LoadBalancer`** -- a cloud L4 load balancer
   and an IP, with no host routing and no TLS termination. Fine for a demo, and it
   forces the SPA onto a different origin.
3. **`kubectl port-forward svc/api-gateway 8080:8080`** -- how to reach the
   aggregated Swagger UI without exposing anything.

**One host for the API and the SPA is the whole design.** There is no CORS
configuration anywhere in this repository: a grep for "cors" returns exactly one
hit, a comment in `api-gateway/src/main/resources/application.yml` noting that the
Swagger UI urls are relative so "there is no CORS configuration to get wrong". A
frontend on its own origin would need CORS added to the gateway -- a code change, a
new allowed-origins env var, and a preflight failure mode that looks like the API
being down. Same-origin routing is what lets the SPA ship a base path of `""` and
build every API URL relative -- `frontend/src/config.ts` asserts the value is relative
and rejects an absolute origin at module load -- so none of that has to be invented.

`ingress.host` must equal the host in `common.publicGatewayUrl`, or Swagger UI's
"Try it out" advertises a cluster-internal name no browser can reach.

`/actuator` is **never** routed through the Ingress, at any setting. The probe paths
and `/actuator/prometheus` are reachable unauthenticated on the pod port -- that is
what makes probes and scraping work -- and publishing them would hand endpoint URIs,
DB pool sizes and JVM internals to the internet.

`ingress.exposeSwaggerUi` is off by default. Publishing a complete, accurate
description of every endpoint in the platform to the internet is a decision, not a
default.

---

## Migrations

**Flyway stays where it is: in the application, during context refresh.**
`migrations.strategy` accepts only `"startup"`, and the chart fails the render on
anything else rather than ignoring it.

It is **not** unsafe with N replicas. On PostgreSQL, Flyway takes a 64-bit advisory
lock (`pg_try_advisory_xact_lock`) before it reads a single script -- before it even
creates `flyway_schema_history`, since that creation is wrapped in the same lock --
so the replica that loses the race waits rather than double-applying, and there is
no stale lock to repair: an advisory lock lives in the Postgres backend and dies
with it, and the scripts run in transactions. It also gives readiness gating for
free and **by construction**: Spring wires every
`@DependsOnDatabaseInitialization` bean, the `EntityManagerFactory` included, behind
the Flyway initializer, so the order is Flyway, then `ddl-auto: validate`, then the
web server binds, then Actuator answers. There is no window in which a pod is Ready
against an unmigrated schema.

What it *is* is bounded. `spring.flyway.lock-retry-count` defaults to 50 retries at
one second, which is a ~50 second **cliff**: a migration slower than that makes
every sibling replica throw and crashloop while the winner finishes. Old pods keep
serving, so the symptom is a stalled rollout that looks exactly like a broken one.
This chart sets it to `-1` (wait indefinitely) and lets the 300s `startupProbe` and
then `progressDeadlineSeconds` be what decides the pod has waited too long -- the
waiting pod is doing the right thing, and the rollout is the right thing to give up.

### Why there is no pre-upgrade Job

A `helm.sh/hook: pre-upgrade` Job is the right answer eventually, and it cannot
work with these images today. It needs a migrate-and-exit mode, and
`FLYWAY_ENABLED=false` is only half of one: four of the six schema-owning services
carry `@EnableScheduling`, Spring's `ThreadPoolTaskScheduler` threads are
non-daemon, and there is no `spring.task.scheduling.enabled` property in Boot 3.5 --
so a same-image "migrate" pod would migrate and then sit there forever, the Job
would never complete, it would hit `activeDeadlineSeconds`, be marked Failed, and
fail the `helm upgrade`. Shipping a broken option is worse than shipping none.

**Move to a Job when any one of these becomes true:**

1. a planned migration's worst case exceeds roughly four minutes;
2. **somebody is on the hook for least-privilege database access** -- this is the
   trigger most likely to fire first, and the one startup-Flyway genuinely cannot
   satisfy. With migrations in the application, the application's own DB role holds
   DDL rights permanently, so a compromised pod or an app-level SQL injection can
   `DROP TABLE` at any time. A Job is what lets the serving Deployment run
   DML-only;
3. a migration needs `CREATE INDEX CONCURRENTLY`, or anything else that cannot run
   inside a transaction;
4. replica counts get high enough that N-1 replicas waiting on the advisory lock is
   a meaningful share of rollout time.

### The migration-authoring rule this chart assumes

During a rolling update, **old code runs against the new schema** for as long as
`maxUnavailable` lets old pods live. So a migration must be additive in the release
that introduces it -- no `DROP COLUMN`, no `RENAME`, no type narrowing, no new
`NOT NULL` without a `DEFAULT` -- with the removal in a later release, after no pod
maps the old column. There are no down migrations and there will not be: Flyway
Community has no `undo`. Rollback works today only because every existing migration
is additive, and nothing in the build enforces that.

---

## Secrets, and rotating the signing key

`secrets.create` is **false** by default, and that is the recommendation. With it
true the chart renders Secrets whose every value is the literal string
`REPLACE_ME__something`: useful for seeing the shape and for proving the wiring end
to end with `helm template`, and useless for running anything, because Postgres will
reject them and `JwtKeyProvider` will throw on a PEM that is not one. That failure
is correct -- a service that cannot sign or verify tokens must fail to start rather
than mint garbage.

Create them out of band (`NOTES.txt` prints the commands, and prefers `--from-file`
because a `--from-literal` lands in the shell history), or point the `secretEnv`
entries at Secrets an **External Secrets Operator** syncs from a real secret
manager. ESO produces an ordinary `Secret`, so nothing in this chart changes when it
lands.

**A Kubernetes Secret is base64, not encryption.** `echo <value> | base64 -d` is
the whole protection: anyone with `get secrets` in the namespace has every
plaintext, and so does anyone with an etcd backup. RBAC is the actual control.
Secrets are encrypted at rest only if the API server has an
`EncryptionConfiguration`, which several distributions ship without, and a Secret
injected as an env var is readable from `/proc/1/environ` inside that container.

There is **no real credential anywhere in this chart** and there must never be one.
[docs/security.md](../../../docs/security.md)'s "there is no signing key in this
repository at all" stops being true the moment somebody edits a placeholder in
place instead of creating the Secret out of band.

### `user-service` and the one shared signing key

The chart **refuses to render more than one `user-service` replica** until
`secrets.jwtSigningKeyProvisioned` is true. `JwtKeyProvider` generates a throwaway
2048-bit RSA pair when `jwt.signing-key.private-key` is blank, so N replicas means N
different keys published under one `kid`, and the failure is not a clean one:

- a login round-robins to pod A and the token says `kid: sdp-dev-key-1`;
- a resource server fetches the JWKS through the Service and may cache **pod B's**
  key -- same `kid`, different modulus;
- verification fails, and Spring Security does **not** recover, because it refetches
  a JWK set only on an **unknown** `kid`. This one is known, just wrong. The 401
  persists for the cache lifespan and then re-lands on a randomly chosen pod;
- the 401 is byte-for-byte the one a forged token produces, so the evidence points
  at the client;
- and it does not stop at logins. `order-service` takes a SERVICE token from pod A
  to `inventory-service`, which verifies against pod B; a 401 is not in that
  breaker's `ignore-exceptions`, so the `inventory-service` circuit opens and orders
  fail 503. **A missing signing key shows up on the dashboard as
  `inventory-service` being down.**

`JWT_PUBLIC_KEY` is deliberately never set: it is derived from the private key, and
supplying a mismatched half publishes a JWKS entry that cannot verify what the pod
signs -- a 100% failure rate, for a value the JVM can compute.

### Rotating it: three steps, two rollouts

`jwt.retired-keys` publishes *additional* public keys, and nothing checks that a
"retired" key is older than the active one. That mechanical detail is what makes a
safe rotation possible: it lets a key be **published before it is signed with**. A
single swap breaks, because mid-rollout the not-yet-replaced pods publish only the
old key, so a verifier that sees the new `kid`, refetches, and load-balances onto an
old pod still gets a 401.

1. **Publish only.** Signing key stays old; add the new key's *public* half to
   `jwt.retired-keys`. Every pod now publishes both. Nothing signs with the new key.
2. **Promote.** Signing key becomes the new one; `retired-keys` becomes the old
   key's public half. Throughout this rollout both `kid`s resolve at every pod, so
   there is no window where a live token's `kid` is unresolvable.
3. **Drop.** After `JWT_EXPIRATION` (1h) plus clock-skew margin -- 2h is sane --
   clear `retired-keys` and delete the old PEM. Retiring *early* is the mistake:
   it 401s every still-valid token for up to an hour, intermittently, as caches
   expire.

Retired keys are **public**, so they belong in a ConfigMap, not a Secret. This chart
does not template that fragment: `jwt.retired-keys` is a list of records holding
multi-line PEMs, which is unreviewable as indexed env vars
(`JWT_RETIREDKEYS_0_PUBLICKEY`) and wants a mounted YAML fragment imported with
`SPRING_CONFIG_IMPORT=optional:file:/etc/sdp/jwks/published-keys.yaml`. Rotation is
a deliberate, human-paced, twice-a-year procedure; templating it would mean carrying
a permanent volume mount for something used twice, and the `optional:` prefix
exists precisely so the pod starts when no rotation is in progress. Add the mount
for the rotation and remove it afterwards.

### `ORDER_SERVICE_CLIENT_SECRET`: one value, two Deployments

`user-service` reads it as the expected secret for
`jwt.service-clients.order-service`; `order-service` reads it as the credential it
presents. Same env var name on both sides, so the chart uses **one Secret key with
two `secretKeyRef`s**. Two copies in two Secrets is the arrangement that drifts and
then stalls every saga with a 401 nobody can attribute.

Rotating it has a window: `ServiceTokenProvider` caches a 5-minute token and
refreshes it 30s early, so **roll `user-service` first and `order-service` within
about 4.5 minutes**. Miss it and orders fail `SERVICE_UNAVAILABLE` until
`order-service` rolls. For no window at all, add a second client id to
`user-service`'s `jwt.service-clients` map, point
`services.order-service.config.ORDER_SERVICE_CLIENT_ID` at it, roll, then drop the
first. (Caveat: an env-var-derived map key cannot contain a hyphen, so the second
id has to be something like `orderservice2`.)

### The bootstrap admin: set neither variable

`BOOTSTRAP_ADMIN_EMAIL` and `BOOTSTRAP_ADMIN_PASSWORD` are deliberately absent from
this chart. `user-service`'s `application.yml` does not declare them either -- a
declared-but-blank property satisfies `@ConditionalOnProperty`, which would turn the
opt-in into an always-on -- so leaving them unset genuinely means "no seeded
account". Two reasons not to set them in a cluster:

1. `BootstrapAdminInitializer` is an `ApplicationRunner`, so it runs in **every**
   replica, and across pods the `findByEmail` check and the insert are separate
   transactions. On a cold database two pods can both miss and both `save()`, and
   `users.email` is `NOT NULL UNIQUE`, so the loser throws out of its
   `ApplicationRunner` and fails startup. It self-heals on restart, but it is a
   confusing crash during a first deploy.
2. The benefit is one-time and the cost is permanent: a static full-ADMIN password
   in etcd and its backups forever, in exchange for one account creation.

Provision the first admin with a **one-boot window** instead: scale `user-service`
to exactly 1 replica with both variables set, confirm the
`CREATED BOOTSTRAP ADMIN ACCOUNT` warning in the log, then remove the variables and
the Secret and roll again. Replicas must be 1 for it, per (1). A Job cannot run the
initializer, because the process is a web server and never exits.

---

## Observability

`observability.serviceMonitor.enabled` renders **one** `ServiceMonitor` for all
eight services, selecting by port **name** so the 8080-8087 spread never appears in
a scrape config.

**Why a ServiceMonitor and not `prometheus.io/scrape` pod annotations.** With
annotations every pod inherits the scrape config's own `job_name`
(`kubernetes-pods`), so all eight services collapse into one `job` value -- and
every panel in `infrastructure/grafana/dashboards/platform-overview.json` aggregates
`by (job)` with a job-templated legend, so the whole dashboard becomes one line per
panel. The Operator derives `job` from the **Service name** instead, which keeps all
15 panels working unedited. `spec.jobLabel` is deliberately unset: pointed at a
label the eight Services share, it would collapse them into one series -- the same
failure, arrived at by accident.

Two labels that will silently waste an afternoon if they are wrong:

- `observability.serviceMonitor.extraLabels` must match the Prometheus CR's
  `spec.serviceMonitorSelector` (`release: <stack release>` with
  kube-prometheus-stack). Wrong, and the object is **ignored** -- no error, no
  target, blank dashboard. Check `/targets`, not the dashboard.
- `observability.prometheusRule.extraLabels` likewise, against `spec.ruleSelector`.

`metricRelabelings` drops `http_server_requests_seconds*` for `/actuator*` URIs.
Under Compose only a container healthcheck hit actuator; here the kubelet probes
liveness **and** readiness every ~10s per pod and Prometheus scrapes every 15s, so
on a quiet cluster probe traffic becomes the majority of
`http_server_requests_seconds_count` -- which corrupts three panels at once: request
rate measures the kubelet, error rate gets an inflated denominator so real 5xx
spikes flatten, and p95 is dragged toward actuator's sub-millisecond responses.
Dropping it once at scrape time means every future query and SLO inherits the fix.
**Verify the regex against one real `/actuator/prometheus` response**: it matches the
`uri` label's value, and if Spring tags actuator requests in some form it does not
match, the rule drops nothing while looking correct.

`observability.prometheusRule.enabled` adds the alerts this repository has never
had -- `infrastructure/prometheus/prometheus.yml` has no `rule_files` at all. The
job regex and the expected job count are generated from the services map. The first
group exists so that "the observability broke" is loud: a failed scrape is
indistinguishable from a healthy-but-quiet service on every panel, and a 401 scrape
lands there too.

**These alerts are not optional in the way the flag suggests.** Liveness is
`livenessState` only, which will essentially never fire -- that is the intent, but
it means a service that is up and completely broken, say a saga wedged on a bug,
will never be restarted by Kubernetes. That detection was deliberately moved to
metrics (`order.saga.outcomes`, `saga.stuck.count`,
`outbox.oldest.pending.age.seconds` all already exist). Probes now detect strictly
less than the naive recipe would, on purpose, and the phase is only complete once
something alerts on those gauges.

`LOGGING_STRUCTURED_ECS_SERVICE_NODE_NAME` and `_ENVIRONMENT` are set from the
downward API, which recovers for logs the one thing this move takes away: the stable
hostname that tied a log line to a process. `service.node.name` matches the
`instance` label the ServiceMonitor relabels, so a metric anomaly and its log lines
join on one string.

`SPRING_PROFILES_ACTIVE` is `"docker,kubernetes"` -- both names. The last `---`
document in all eight `application.yml` files is what turns on ECS JSON logging, and
its activation is the profile expression `"docker | kubernetes"`. Activating both
means the JSON logging is on whether or not that widening is present in the image
being deployed, and stays on if a `kubernetes`-only document is added later. The
failure it avoids is the quiet one: a single wrong profile name does not error, it
silently reverts to plain text.

---

## The shutdown budgets, and where the numbers come from

Three fields are **one decision written in three places**, and the services' own
`application.yml` files say exactly that: `preStopSleepSeconds`,
`shutdownPhaseTimeoutSeconds` (which the chart passes as `SHUTDOWN_PHASE_TIMEOUT`,
the env var those files read into
`spring.lifecycle.timeout-per-shutdown-phase`) and
`terminationGracePeriodSeconds`. **The chart fails the render if they disagree**, so
they cannot drift apart the way three numbers in three files normally do.

`timeout-per-shutdown-phase` bounds **each** `SmartLifecycle` phase independently --
it is not a total. The phases, read off the 3.5.13 / 6.2.17 / 3.3.14 jars:

| phase | bean | which services |
|---|---|---|
| `2147483547` | Kafka listener containers (`AbstractMessageListenerContainer`) | order, delivery, notification |
| `2147482623` | graceful request drain (`WebServerGracefulShutdownLifecycle`) | all eight |
| `2147481599` | web server stop (`WebServerStartStopLifecycle`) | all eight |
| `1073741823` | the `@Scheduled` pool (`ExecutorConfigurationSupport`) | inventory, order, payment, delivery |
| `-2147483648` | the shared Kafka producer (`DefaultKafkaProducerFactory`), closed with a **30s** `DEFAULT_PHYSICAL_CLOSE_TIMEOUT` that Boot exposes no property to shrink | inventory, order, payment, delivery, notification |

Two things on that list are easy to get wrong in opposite directions. The web-server
stop is a **separate phase** from the request drain, so even a service with nothing
else in it has two phases and not one. And `applicationTaskExecutor` is **not** on
it: Boot 3.5 annotates that bean `@Lazy`, so nothing instantiates it in these
services and it never joins the lifecycle. It would the day one of them grows an
`@Async` method, and phase `1073741823` would gain an occupant.

The resulting numbers:

| service | preStop | phase timeout | grace |
|---|---|---|---|
| api-gateway | 5 | 15 | **40** (`5 + 15 + 20`; twice the phase timeout is only 35, so the headroom is explicit) |
| the other seven | 5 | 20 | **45** (`5 + 20 + 20`) |

### Why 45 and not 135

Summing every phase's worst case plus the producer close gives roughly 135s for
`order-service`, and that is the wrong budget. A pod that reaches it is a pod that is
**stuck**, so sizing for it means every stuck pod holds a rollout slot for over two
minutes -- while a SIGKILL at that point is **safe** here: an interrupted outbox batch
rolls back to PENDING ([ADR 006](../../../docs/adr/006-outbox-concurrency-and-ordering.md))
and a claimed saga's lease expires so a peer picks it up
([ADR 008](../../../docs/adr/008-reliable-compensation-and-stuck-saga-reaper.md)). So
the budget covers the drain being used **in full** with everything else behaving, and
the pathological tail is deliberately left to SIGKILL.

The way to make that tail cheaper is to shrink its dominant term, not to inflate the
budget: a `ProducerFactory` customizer calling `setPhysicalCloseTimeout(5)` in
`platform-starter`, next to the outbox code that owns the trade-off. A realistic
shutdown here is one or two seconds, and `maxSurge: 1` / `maxUnavailable: 0` is what
keeps a slow one from costing capacity.

*(An earlier draft of this chart used the sum -- 125s for `order-service`. It was
wrong for the reason above, and the corrected numbers are the ones the services'
own comments derive, so the chart and the config now agree rather than contradicting
each other in two files.)*

### The per-probe grace override

Both the liveness and startup probes carry `terminationGracePeriodSeconds: 10`, the
per-probe override (Kubernetes 1.25+). A container that failed liveness is by
definition one whose graceful shutdown cannot complete -- a wedged thread pool or GC
thrash on the way to an OOM is exactly what stops the drain finishing -- so the
pod-level budget buys nothing there and costs the whole detection-to-replacement
window. Without it, a wedged `order-service` replica is out of rotation for 15s
(readiness) + 45s (liveness) + 45s (grace) + a boot.

### `spring.task.scheduling.shutdown.await-termination` is deliberately not set

It reads like a safer, longer wait for an in-flight `@Scheduled` task and it is the
exact opposite. In `ExecutorConfigurationSupport`,
`onApplicationEvent(ContextClosedEvent)` sets `lateShutdown = true` whenever
`waitForTasksToCompleteOnShutdown` is set and then **returns** -- skipping
`markShutdown()` and `initiateEarlyShutdown()` -- while `stop(Runnable)` tests the
same flag and fires the phase callback immediately. So setting it makes phase
`1073741823` complete in microseconds, stops pausing submission, and lets a fresh
outbox poll start during the request drain and run on into bean destruction, by which
point the shared producer has already been closed in phase `-2147483648`.
`OutboxPublisher.publishOne` catches `Exception` and calls `recordPublishFailure`, and
Micrometer counters are not transactional, so every rolling deploy would put a
batch-sized spike on `outbox.publish.failures` -- the metric
[docs/observability.md](../../../docs/observability.md) says to alert on -- which is
precisely what the property normally gets added to prevent.

The default path is the one that behaves: submission stops at `ContextClosedEvent`,
and the phase timeout bounds the wait for the task already running. What the chart
contributes here is `SCHEDULER_POOL_SIZE` (3 for `order-service`, 2 for the other
three schedulers): Boot's default of 1 means the outbox publisher holding its
transaction across a batch is time in which the cleanup job and the stuck-saga reaper
do not run **at all**, which reads as "the reaper is broken" and is really "it never
got a thread". More replicas do not fix that -- each one has the same single thread.

## The probes, and the one thing they do not decouple

All three probes point at the two probe groups, never at the aggregate
`/actuator/health`. The aggregate includes the DataSource -- plus Redis in
`product-service` and `circuitBreakers` in `order-service` -- and pointing a kubelet
at it turns a Postgres blip into a platform-wide CrashLoopBackOff: every replica of
every service fails the same probe inside one `failureThreshold` window, gets
killed, and then cannot come back, because Flyway runs at boot and fails the context
while the database is still unreachable. **A dependency a restart cannot fix does not
belong in liveness.** The aggregate is left exactly as it was, for
docker-compose's `depends_on` and for a human with `kubectl`.

Readiness excludes the shared dependencies for a different reason: every replica
points at the same database, so the failure is never per-pod. All replicas would
fail together, the EndpointSlice would drop to zero endpoints, and callers would get
a connection-level failure with **no response body** instead of the
503-with-a-correlation-id that `platform-starter`'s error contract exists to
produce. The failure that accepts, stated plainly: a pod whose *own* database
connectivity is broken while its peers are fine stays in rotation and serves 503s
for its share of traffic. That is visible on the error-rate panel and in the
correlation ids, and it is left as a human decision.

**What removing `db` from the groups does not fix, and this chart's answer to it.**
Both probe groups are served by the application's own Tomcat worker pool, because
nothing in this repository sets `management.server.port`. So "can this JVM answer at
all" is *not* independent of Postgres -- it is coupled through the request threads.
With HikariCP's default 30s `connection-timeout`, a slow database parks all 200
workers in `getConnection()`, the kubelet's probe is accepted at the socket and
never dispatched, it blows `timeoutSeconds`, and the platform-wide restart storm
arrives anyway by another route.

The chart mitigates it with `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT=2000`: a
saturated pod **sheds** load instead of accumulating threads, so a request that
cannot get a connection fails fast into the platform's own error contract, the
worker returns to the pool, and a thread stays free to answer the probe. It trades
requests that would have succeeded after a long wait for a pod that stays alive and
diagnosable.

The real fix is `services.<name>.managementPort`, which this chart supports and
leaves at `0`. Set it (uniformly -- the chart fails a mixed release, because one
ServiceMonitor endpoint selects one port name) and the chart sets
`MANAGEMENT_SERVER_PORT`, adds a second container and Service port named `mgmt`, and
points all three probes and the scrape at it. **Read this before using it:** the main
context's `SecurityFilterChain` does not apply to Spring Boot's management child
context, so everything in `management.endpoints.web.exposure.include` becomes
reachable **unauthenticated** by anything that can reach the pod IP. The
NetworkPolicy is what bounds that, and the port is never routed through the Ingress.
It also means the `SecurityConfig` changes in
[prerequisites](#prerequisites-in-the-application-config) become unnecessary, and
actuator traffic stops polluting the main context's HTTP metrics.

---

## The React frontend

`frontend.enabled` is **false** by default because no image is published for it: CI's
`docker-build` matrix covers the eight Java services only, and `frontend/` has no
Dockerfile yet. The Deployment, Service, HPA and PDB are templated and ready.

**There is no ConfigMap for this workload, and it needs none.** Worth stating,
because the obvious design -- mount a `/config.json` the app fetches at boot so that
one image serves every environment -- is what this chart originally carried, and the
app that got built does something better. `frontend/src/config.ts` reads a
**build-time** `VITE_API_BASE_PATH` that is `''` in every environment it ships,
asserts at module load that the value is a *relative* path (rejecting an absolute
origin, a protocol-relative `//host` and a no-leading-slash path, each with its own
error), and `frontend/src/lib/api/http.ts` -- the only `fetch` in the app -- builds
every URL from it. So the bundle carries no origin, no hostname and nothing
environment-specific: the same artifact is correct everywhere, with no runtime config
to mount, no 404-fallback path to get right, and nothing for a ConfigMap to hold.
Mounting one would be dead weight the app never reads.

The chart is what pays for that guarantee: **the SPA must be served from the same
origin as the API**, because relative URLs have no other meaning. `ingress.host` is
what provides it, and the app refuses an absolute origin outright rather than letting
someone reintroduce a cross-origin setup that this platform has no CORS configuration
for.

**The rest of the contract the chart assumes**, none of which it can check while the
Dockerfile does not exist:

- serves on **containerPort 8080 as UID 1000**, not port 80. The pod drops `ALL`
  capabilities including `NET_BIND_SERVICE`, so a root-nginx image that binds 80 will
  not start. `nginxinc/nginx-unprivileged` is the usual base;
- runs with a read-only root filesystem, writing only to the `emptyDir` mounts the
  chart provides at `/tmp`, `/var/cache/nginx` and `/var/run`;
- serves `index.html` for unknown paths, because a client-side router needs that and
  the Ingress sends every unmatched path here.

It is also the one workload here with no `startupProbe` and no argument about probe
groups: a static file server starts in milliseconds and has no dependency to wait on,
so the naive recipe is the right one.

**This workload gets no Secret, ever.** A browser bundle has no confidentiality --
anything the page can read, so can the person reading it -- so
`ORDER_SERVICE_CLIENT_SECRET` (which would let any visitor mint SERVICE-role tokens)
and `BOOTSTRAP_ADMIN_PASSWORD` must never be within reach of it. There is
deliberately no `secretEnv` field for the frontend anywhere in `values.yaml`.

It carries no `sdp.metrics/scrape` label either: nginx has no `/actuator`, and
labelling it would put a permanently-down target on the dashboard. That is exactly
the case the opt-in label exists for.

**One product consequence to state before somebody tries to solve it in React:** this
app inherits an authentication design with no refresh tokens and no revocation, both
recorded as out of scope in [docs/security.md](../../../docs/security.md). A one-hour
access token in the browser, unrenewable, means the user is logged out mid-session
with no way to extend it, and a logout cannot actually invalidate anything. That is a
backend decision surfacing in the UI, not a frontend bug.

## Naming, and why it breaks Helm convention

Object names are **not** prefixed with the release name. `naming.prefix` defaults to
`""`, so the Services are `order-service`, not `sdp-order-service`.

That is deliberate. The Prometheus Operator derives the `job` label from the Service
name; all 15 panels in `platform-overview.json` aggregate `by (job)` with a
job-templated legend; and the eight `job_name` values in
`infrastructure/prometheus/prometheus.yml` are the bare service names. Prefixing
renames every legend and silently stops the dashboard matching the platform it
points at.

The cost, plainly: **two releases of this chart cannot coexist in one namespace.**
Install a second one into its own namespace, or set `naming.prefix` and accept that
the `job` label changes with it. The chart's own PrometheusRule builds its job regex
from the prefix, so the alerts follow automatically; the Grafana dashboard JSON does
not, and would have to be edited.

Three tiers, so that a prefixed release does not read `prod-sdp-common`:

| objects | default name | with `naming.prefix=prod-` |
|---|---|---|
| workloads -- Deployment, Service, HPA, PDB | `order-service` | `prod-order-service` |
| ConfigMaps and the singleton ServiceMonitor / PrometheusRule / Ingress / NetworkPolicy | `sdp-order-service`, `sdp-common`, `sdp-platform` | `prod-order-service`, `prod-common`, `prod-platform` |
| Secrets | exactly what `secretEnv[].secretName` says | unchanged |

Secrets are unprefixed on purpose: they are literal references to objects this chart
does not own, created out of band or by an External Secrets Operator, so what an
operator writes in values is what appears in the manifest. Two releases in one
namespace would share them, which is usually what is wanted for the signing key and
never what is wanted for a database role -- give the second release its own names.

---

## Validation guards

`helm template` fails, with the reasoning in the error message, on:

| condition | why it is a guard and not a warning |
|---|---|
| `migrations.strategy != "startup"` | the alternative cannot work with these images |
| more than one `user-service` replica (or its HPA) while `secrets.jwtSigningKeyProvisioned` is false | intermittent platform-wide 401s that read as `inventory-service` being down |
| an HPA on a service with `hasOutbox` while `outbox.scalePollInterval` is true | the poll interval would be derived from a replica count nothing respects |
| `OUTBOX_POLL_INTERVAL_MS` set by hand in a service's `config` | two values for one ConfigMap key |
| `secrets.create` true with a `secretEnv` naming a Secret that has no placeholder | the pod would sit in `CreateContainerConfigError` |
| `managementPort` set on some enabled services but not all | one ServiceMonitor endpoint selects one port name, so the rest would all be down |
| `ingress.enabled` with an empty `host`, or with `api-gateway` disabled | a host-less rule matches every request the controller receives |

A PodDisruptionBudget is **skipped** (not an error) for any service whose replica
floor is 1, even when `podDisruptionBudget.enabled` is true: a PDB on a
single-replica Deployment makes the eviction API refuse forever, so `kubectl drain`
hangs and the message points at the PDB rather than the replica count.

---

## Known gaps this chart does not fix

Things reviewed, understood, and deliberately left alone -- mostly because the fix
is code or configuration outside `deploy/helm/`.

1. **`OrderService.buildOrder` calls `product-service` once per line item, and
   `CreateOrderRequest.items` has `@NotEmpty @Valid` but no `@Size(max=...)`.** The
   20s drain budget is sized on *one* call's worst case (~16s through the
   Resilience4j retry chain), so a two-item order already exceeds it and the list is
   unbounded. During a rolling deploy with a degraded `product-service`, an in-flight
   multi-item POST is cut off mid-request -- a TCP reset on a non-idempotent
   `POST /api/v1/orders`, with no 503 and no correlation id to grep for. The fix is a
   size cap on the request or a request-level `@TimeLimiter`, and then the shutdown
   arithmetic has to be redone.
2. **The `@Scheduled` pool's own shutdown wait is a trap, and is left unset.** See
   [the shutdown section](#springtaskschedulingshutdownawait-termination-is-deliberately-not-set):
   setting `spring.task.scheduling.shutdown.await-termination` *removes* the phase
   wait it looks like it adds, and would put a batch-sized spike on
   `outbox_publish_failures_total` on every deploy. It is correctly absent from the
   services' config; this entry exists so nobody adds it as an "improvement". If you
   ever see that counter spike on every rolling deploy, check whether it came back.
3. **`ADR 005`'s promise that a Redis outage falls through to Postgres is not
   implemented.** `CacheConfig` registers no `CacheErrorHandler`, so a Redis outage
   throws out of the `@Cacheable` interceptor and 500s product reads. Keeping
   `redis` out of readiness is still right -- a gate would trade 500s for zero
   endpoints -- but the honest fix is a `CacheErrorHandler`.
4. **Neither Redis nor Kafka authentication is bindable.** `product-service` sets
   only `spring.data.redis.host` and `.port`, and nothing anywhere configures
   `spring.kafka.security.*`. A managed Redis or broker that requires auth needs
   those properties added first. Setting a `REDIS_PASSWORD`-shaped env var here
   would be **silently ignored**, and the failure would look like a connection
   refusal.
5. **Two Grafana dashboard panels are wrong as soon as a service has two
   replicas**, and the JSON is outside this chart's paths. Panel 9 uses
   `sum by (job) (outbox_pending_count)` where the gauge queries the database at
   scrape time, so every replica reports the same global number and the panel reads
   Nx the real backlog (panel 10 correctly uses `max by (job)`). Panel 15
   (`rate(saga_reaper_retried_total[15m])`) has no aggregation at all with a
   constant legend, so two replicas draw two identically-labelled lines. The chart's
   PrometheusRule uses `max` for exactly this reason. Panel 8 is misleading rather
   than wrong for the same reason -- `sum by (job) (hikaricp_connections_active) /
   sum by (job) (hikaricp_connections_max)` hides one pod pinned at 100% behind two
   idle ones, which is precisely the case a pool-saturation panel exists to catch;
   `max by (job) (active / max)` is the per-pod question. (Verified against
   `platform-overview.json` version 3, not quoted from memory.)
6. **A ServiceMonitor on `role: endpoints` only scrapes *ready* pods.** A
   crash-looping pod drops out of the target list entirely, so it is invisible in
   metrics exactly when you want it, and `up == 0` cannot fire for a series that no
   longer exists (`SdpPlatformJobMissing` only catches losing every replica of a
   job). A second headless Service with `publishNotReadyAddresses` is **not** the
   fix: the Operator derives `job` from the Service name, so it would split every
   panel. Pod-level health belongs to kube-state-metrics.
7. **`spring.flyway.lock-retry-count` at Flyway's default of 50 is a ~50 second
   cliff.** This chart sets `-1`, but only if `application.yml` declares the
   `${FLYWAY_LOCK_RETRY_COUNT}` placeholder.
8. **The gateway's keep-alive pool was unbounded, and the chart's fix is
   unverified.** `HttpClientProperties$Pool` leaves `maxIdleTime` and `maxLifeTime`
   null with `evictionInterval` at zero, and `api-gateway`'s `application.yml`
   configures no `pool` block -- so the gateway held pooled TCP connections to
   backend pods indefinitely, pinned through conntrack where neither EndpointSlice
   removal nor a preStop sleep touches them. A backend's idle socket is FIN'd at
   connector stop, and a request that leases it in that window fails with
   `PrematureCloseException` -> 500 on a non-idempotent POST. The 5s preStop makes
   that *more* likely, not less, because it keeps the socket leasable for five extra
   seconds. The chart sets
   `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_{MAX_IDLE_TIME,MAX_LIFE_TIME,EVICTION_INTERVAL}`
   as env vars, which should bind through relaxed binding without a code change --
   but nothing has run, so treat it as reasoned rather than proven, and consider
   pinning `server.tomcat.keep-alive-timeout` on the backends so the relationship is
   written down on both sides.
9. **No Kafka consumer-lag alert**, which is the metric that would actually tell you
   `notification-service` has stopped working. There is no Kafka health indicator on
   that classpath and no kafka-exporter in this repository, so the series does not
   exist. It is the most valuable missing alert.
10. **Nothing enforces that a migration is additive**, and there are no down
    migrations. See [Migrations](#migrations).

---

## What has actually been verified

**No cluster. No container runtime. No `helm install`.** What was done instead, and
it is more than the phrase "unverified chart" usually implies:

- **`helm lint` passes**, with one INFO: the chart has no `icon`. Deliberate -- there
  is no image asset in this repository to point at, and a made-up URL would be worse
  than the INFO.
- **`helm template` renders cleanly in 22 value combinations**: the defaults,
  `values-production.yaml`, both of those with `naming.prefix` set, and every
  optional block on its own -- `secrets.create`, Ingress with and without TLS / the
  Swagger routes / the JWKS route, the frontend with its HPA and PDB, both
  NetworkPolicies, the ServiceMonitor with and without the actuator drop, the
  PrometheusRule, topologySpread with and without the zone constraint, the `exec`
  preStop form, the downward-API fields off, a fixed outbox poll interval, a uniform
  management port, a single-service install, and extra pull secrets / node selectors
  / tolerations. Every one parses as YAML and the object set was read back and
  checked field by field: probe ports and paths, env wiring, secret references,
  labels and selectors, the generated service URIs, the derived outbox poll
  interval, the connection arithmetic in both of its branches.
- **`kubeconform -strict` validates the rendered objects against the published
  Kubernetes API schemas.** With every optional object turned on -- the production
  overlay plus placeholder Secrets plus the frontend -- that is 52 objects: **50
  valid, 0 invalid, 2 skipped**, the two skips being the `monitoring.coreos.com`
  CRDs, whose schemas are not in the schema store. Those two (the ServiceMonitor and
  the PrometheusRule) therefore have the least coverage of anything here, so check
  them on first apply. The escape-hatch configuration validates clean at 1.25 as
  well. `-strict` rejects unknown fields, so this is what establishes that
  `lifecycle.preStop.sleep`, `probe.terminationGracePeriodSeconds` and
  `matchLabelKeys` are real fields and not plausible-looking inventions, and it is
  what produced the version table above.
- **Every validation guard was fired on purpose** to confirm it triggers and that
  its message says what to do.
- **The chart's numbers were checked against the config they have to agree with.**
  Every `application.yml` in the repository was read after the probe, shutdown and
  migration work landed, and the chart was corrected where it disagreed: the grace
  periods now match the arithmetic those files derive (45, and 40 for the gateway),
  the chart passes `SHUTDOWN_PHASE_TIMEOUT` and `SCHEDULER_POOL_SIZE` so the two
  halves of that arithmetic come from one place, and the frontend's runtime
  `config.json` was removed once the app turned out to use a build-time relative base
  path instead. A chart that contradicts the config it deploys is worse than no
  chart.
- `NOTES.txt` was rendered in several value combinations, including both branches of
  the connection-budget arithmetic.

What is **still** unverified, and cannot be claimed:

- **No kubelet has ever read one of these probe stanzas.** The periods, timeouts and
  thresholds are reasoned from what each service does at startup and shutdown, not
  measured. Re-measure them against observed boot and shutdown times on the first
  real cluster.
- **No scrape has ever hit `/actuator/prometheus` through a ServiceMonitor**, so the
  claim the whole naming decision rests on -- that the Prometheus Operator derives
  `job` from the Service name -- is documented Operator behaviour rather than
  observed behaviour. **Check Prometheus `/targets` on the first apply, before
  trusting any panel.** The same goes for the `metricRelabelings` regex, which
  matches the `uri` label's value: read one real `/actuator/prometheus` response and
  grep it.
- **No pod has ever been asked to shut down inside one of these grace periods**, and
  the phase arithmetic behind them was derived by reading framework behaviour rather
  than by watching a shutdown.
- **A schema is not an admission controller.** Nothing here was checked against a
  PodSecurity standard, a LimitRange (which may reject a container with no CPU
  limit), an OPA/Gatekeeper policy, or a real ingress controller's path-matching
  behaviour.
- **The Spring env-var bindings the chart relies on were not exercised against a
  running context**: `SPRING_DATASOURCE_HIKARI_*`,
  `SPRING_CLOUD_GATEWAY_SERVER_WEBFLUX_HTTPCLIENT_POOL_*`, `MANAGEMENT_SERVER_PORT`
  and `LOGGING_STRUCTURED_ECS_*`. They follow Spring Boot's documented relaxed-binding
  rules, and a wrong one fails *silently* -- the property is simply never set. No
  Maven build was run either.
- **Nothing was deployed, so nothing was rolled back.** The rollout strategy, the
  PDB behaviour under a real drain, and the HPA's interaction with the
  `replicas`-absent Deployment are all untested.

This is the same class of gap [docs/observability.md](../../../docs/observability.md)
already records for the Grafana dashboards, extended to Kubernetes. Treat the first
apply as the test, and fix the numbers from what it shows.
