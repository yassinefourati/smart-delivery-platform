# Load testing

> Phase 23, [ADR 015](adr/015-capacity-no-remote-calls-in-transactions.md). The suite is
> `load/sdp-load.js` ([k6](https://k6.io), v2.3.0 or later). The manual workflow that runs
> it in CI is described in [ci-cd.md](ci-cd.md#load-test-manual-phase-23).

Functional tests answer "does it work?". A load test answers "how does it fail?". The
answer to that is a design property, and the functional suite can't see it. This page
covers how to run the suite, how to read what it prints, and what it found the first
time it ran.

## What the script does

Everything goes through the gateway, as a browser would, so every number includes the
gateway hop, JWT verification and the service-to-service calls behind it. Two scenarios
run together:

- **`traffic`** is the pressure. It's an *arrival-rate* executor: k6 starts N iterations
  per second whether or not earlier ones have finished. A fixed pool of virtual users
  would slow down when the system does, and a slow system would get a lighter load,
  which hides exactly the thing being measured. Each iteration is either a catalog page
  (`catalog`), or, with probability `ORDER_RATIO`, an order (`create_order`) followed by
  a status read of it (`order_status`).
- **`saga_probe`** is two users that each place an order and then poll its status every
  0.5s until the saga reaches `PAID` or later (success), `FAILED`/`CANCELLED` (failure),
  or a minute passes (also failure). A `201` from `POST /orders` only means "accepted".
  Reserving stock, charging and creating the shipment happen afterwards over Kafka, and
  the probe measures that part, under the pressure of the other scenario.

`setup()` runs once. It creates `PRODUCTS` products with ten million units of stock each,
so a `FAILED` order means the platform failed, not that the test sold out. It also
registers and logs in `USERS` customers, each with an address. Logging in happens once
per customer, not per iteration, so the test measures ordering, not BCrypt. Login cost
is a separate question, noted below.

### Profiles

`PROFILE` picks the shape of `traffic`, as arrival rates (iterations/s). `RATE`
multiplies every rate, so `RATE=0.25` turns a production-sized profile into one a laptop
can drive without mostly measuring the laptop.

| Profile | Shape | The question it answers |
|---|---|---|
| `smoke` (default) | 2/s for 30s | Does the script work against this stack? Run it first. |
| `load` | ramp to 20/s, hold `HOLD` (default 8m), ramp down | At expected peak traffic, are the thresholds met with margin? |
| `stress` | 20 → 50 → 100 → 200/s in 3-minute steps, then 5/s for 3 minutes | Where does it break, what breaks first, and does it recover by itself once the pressure goes? |
| `spike` | 5/s, then 150/s within 10s for a minute, then back | What happens when traffic arrives faster than any autoscaler can react? |
| `soak` | 20/s for `SOAK_DURATION` (default 2h) | Does anything leak or accumulate: heap, connections, outbox rows, consumer lag? |

### Settings

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY` | `http://localhost:8080` | Base URL. |
| `PROFILE` | `smoke` | See above. |
| `RATE` | `1` | Multiplier on every arrival rate in the profile. |
| `ORDER_RATIO` | `0.2` | Fraction of `traffic` iterations that place an order. |
| `PRODUCTS` | `5` | Orders are spread over this many products. `1` makes every order contend for the same inventory row, which is the worst case. |
| `USERS` | `40` | Customers created in `setup()`. |
| `HOLD` | `8m` | Steady-state length of `load`. |
| `SOAK_DURATION` | `2h` | Steady-state length of `soak`. |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | Compose's bootstrap admin | Used by `setup()` to create products and stock. |

## Running it

Against the Compose stack:

```bash
docker compose up -d --build        # wait until every container is healthy
k6 run load/sdp-load.js                                        # smoke first
k6 run -e PROFILE=stress -e RATE=0.25 load/sdp-load.js
k6 run -e PROFILE=load -e HOLD=3m --summary-export out.json load/sdp-load.js
```

Or on GitHub: **Actions → Load test → Run workflow**, choosing the profile, the rate and
the hold. The job brings up Compose on the runner, runs k6 and publishes the summary. The
full output and the JSON summary are kept as the `k6-<profile>` artifact.

Against a real cluster, point `GATEWAY` at it and set `ADMIN_EMAIL`/`ADMIN_PASSWORD`. Run
it in staging, not production. `setup()` creates products, a warehouse and customers
named `Load …`, and the orders it places are real orders.

Some ground rules that make the numbers worth something:

- **Keep k6 off the machine you're measuring.** On a laptop they share cores, and past a
  point you're measuring the laptop. That's what `RATE` is for.
- **Change one thing between runs**, and keep the parameters in the file name.
- **Before-and-after needs the same stack, the same parameters and the same data
  volume.** A fresh database and one with a million orders are different systems.

## Reading the result

k6 exits with code 99 when a threshold is crossed. The thresholds are the platform's
current performance budget:

| Threshold | Budget | Why this number |
|---|---|---|
| `http_req_failed` | < 1% | Includes 503s. A shed request is still a failed request for the customer. |
| `http_req_duration{name:catalog}` | p95 < 300ms | A cached read through the gateway. Anything slower is queueing. |
| `http_req_duration{name:create_order}` | p95 < 800ms | Pricing plus one insert. |
| `http_req_duration{name:order_status}` | p95 < 300ms | One indexed read. |
| `saga_completed` | > 99% | The share of probe orders that reached `PAID` or later within a minute. |
| `saga_time_to_paid` | p95 < 10s | From `POST` to `PAID` or later, as the probe saw it. |

Also printed:

- **`responses_503`**: shed load, counted separately from other failures. A 503 is the
  platform protecting itself (pool timeout, open circuit breaker, rate limiter), which is
  a different finding from a 500.
- **`saga_failed`**: probe orders that ended `FAILED` or `CANCELLED`.

What to look at alongside it, in Grafana or `/actuator/prometheus`:

| Signal | What it tells you |
|---|---|
| `hikaricp_connections_pending` | Requests waiting for a database connection. Should be zero. `SdpDbPoolSaturated` fires after 5 minutes of it. |
| `hikaricp_connections_active` vs `_max` | How close the pool is. |
| `order_create_pricing_seconds` | How long a new order waits on product-service. `SdpOrderPricingSlow` fires at p95 > 1s. |
| `http_client_requests_seconds{uri=...}` | Each downstream call order-service makes, per endpoint. |
| `resilience4j_circuitbreaker_state` | Whether a breaker opened. The 503s it causes are intended. |
| consumer-group lag (`kafka-consumer-groups --describe`) | Saga steps waiting to be processed. If lag keeps growing, the saga is falling behind. |
| order status counts (`select status, count(*) from orders group by 1`) | Where the backlog is sitting. |

**Failure modes, from least to most serious:**

- Latency rises but thresholds hold: that's headroom being used up.
- 503s: shedding, by design.
- The saga falls behind while HTTP is fine: that's queueing, and it's invisible to a
  load test that only looks at HTTP. That's why the probe exists.
- 500s, or a stack that doesn't recover in `stress`'s final 5/s stage: those are bugs.

## What Phase 23 found

The suite first ran against `main` as it was after Phase 22, and then again after the
fixes in [ADR 015](adr/015-capacity-no-remote-calls-in-transactions.md). **These numbers
come from a 4-core sandbox**, running all eight services as plain JVMs (no containers)
on one shared PostgreSQL, Kafka and Redis, with k6 on the same machine. So they compare
before with after. They are not a statement of production capacity.

Latency was injected with a small TCP proxy between order-service and the dependency,
which delays each request by a fixed amount. The two settings were:
- product-service slow (+1.5s);
- inventory-service slow (+200ms per call; each saga makes two).

"Before" is `main` at e16bcc2, run with production's old values: pool 4, the broker's
default of 1 partition, 1 listener thread. "After" is this phase, with production's new
values: pool 10, 6 partitions created by the services themselves, 3 listener threads.
Every run used the same parameters on both sides.

RESULTS_PLACEHOLDER

## What this does not cover

- **Login.** `setup()` logs in once per customer, so BCrypt cost at the configured
  strength is not in these numbers. A login storm (a mass logout, or a mobile app that
  re-authenticates on every launch) is a CPU question for user-service. Nothing
  rate-limits login yet; it is the first of ADR 015's follow-ups.
- **Real topology.** It was one pod per service, a shared Postgres, and a single-broker
  Kafka with no replication. Production has 2+ pods, a CloudNativePG cluster per
  service with synchronous replication (commits wait for a replica), and 3-way
  replication on the broker. Both kinds of replication add latency these runs don't
  have.
- **Autoscaling.** The HPAs react in tens of seconds to minutes. `spike` shows what
  happens before they do, and only a cluster can show what happens after.
- **Data volume.** Every run started on near-empty tables.

A staging cluster with production values is the place for the numbers that matter. This
suite runs unchanged against it.
