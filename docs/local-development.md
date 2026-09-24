# Local Development

## Prerequisites

- Java 21 (JDK)
- Maven 3.9+ (or use the system Maven; no wrapper is checked in yet)
- Docker + Docker Compose v2

## Running everything

```bash
docker compose up -d --build
```

Starts, on one Docker network (`smart-delivery-net`):

| Container | Purpose | Host port |
|---|---|---|
| `sdp-postgres` | One Postgres 17 instance, one database per service | 5432 |
| `sdp-kafka` | Kafka 4.3 in KRaft mode (no ZooKeeper) | 9092 |
| `sdp-redis` | Redis 7.4 | 6379 |
| `sdp-api-gateway` | Edge routing | 8080 |
| `sdp-user-service` | | 8081 |
| `sdp-product-service` | | 8082 |
| `sdp-inventory-service` | | 8083 |
| `sdp-order-service` | | 8084 |
| `sdp-payment-service` | | 8085 |
| `sdp-delivery-service` | | 8086 |
| `sdp-notification-service` | | 8087 |
| `sdp-web` | The React SPA behind nginx; proxies `/api` to the gateway -- **open this one** | 8088 |
| `sdp-prometheus` | Metrics, scraping every service's `/actuator/prometheus` | 9090 |
| `sdp-tempo` | Distributed tracing backend (OTLP ingest + query) | 3200, 4318 |
| `sdp-grafana` | Dashboards, provisioned Prometheus + Tempo datasources | 3000 |

Every service depends on Postgres/Kafka reporting healthy before it starts, and the
gateway depends on every backend service reporting healthy before it starts. Check
overall status with:

```bash
docker compose ps
curl http://localhost:8080/actuator/health
```

Once the stack is up, every service's API is browsable from one page:
**<http://localhost:8080/swagger-ui.html>**, with a dropdown to switch between services
(see [api-documentation.md](api-documentation.md)). The individual services no longer
serve a Swagger UI of their own; set `springdoc.swagger-ui.enabled=true` on one if you
want it back while debugging against its own port.

The storefront and the staff screens are at **<http://localhost:8088>**. Sign in as the
bootstrap admin (`admin@smart-delivery.local` / `local-dev-only-admin-password`) to create
categories, products, warehouses and stock; register a new account to shop. For frontend
work without rebuilding the image, run the platform and then `npm run dev` in `frontend/`
(Vite on :5173, proxying to the gateway) -- see [frontend.md](frontend.md).

An empty platform has nothing to browse. `scripts/seed-demo-data.sh` fills it through the
gateway: 5 categories, 20 products, 2 warehouses with stock chosen to show every stock
state (plenty, "only N left", out of stock), 3 customers with addresses, and one order
each, which the saga takes to "waiting for a courier" on its own. It is safe to re-run --
it skips what exists, and the orders carry a fixed `Idempotency-Key`, so a second run
replays them rather than placing more. `SEED_ORDERS=0` skips the orders; the customers
sign in with `demo-password-123`. It creates no delivery agents: to deliver an order, an
admin grants a user `DELIVERY_AGENT` on the **Users** page, creates their agent profile on
**Agents**, and assigns the shipment. The agent then signs in and marks it delivered.

```bash
docker compose up -d --build && scripts/seed-demo-data.sh
```

`scripts/e2e-smoke.sh` drives a complete customer journey against this stack through the
gateway -- register, stock a product, place an order, follow it to `PAID`, oversell,
cancel and refund. `SKIP_BUILD=1` reuses already-built images and `KEEP_STACK=1` leaves
the stack up afterwards.

Tear down with `docker compose down` (add `-v` to also drop the Postgres/Redis data
volumes).

## Running a single service outside Docker

Useful while iterating on one service — start its infra dependencies in Docker, run the
service itself from your IDE or Maven:

```bash
docker compose up -d postgres kafka redis
mvn -pl user-service -am spring-boot:run
```

## Building and testing without Docker

```bash
mvn clean install                # build + unit test every module
mvn -pl order-service -am test   # a single module
```

Integration tests that need Postgres/Kafka/Redis use Testcontainers (from Phase 13
onward) and will start/stop their own throwaway containers automatically — they do not
depend on `docker compose up` being run first, and do not touch the `sdp-*` containers
above.

## Connecting to a service's database directly

```bash
docker exec -it sdp-postgres psql -U sdp -d user_db
```

(Substitute the database name — `product_db`, `inventory_db`, etc. — for the service
you want. Credentials are the fixed local-dev defaults in `docker-compose.yml`; see
[database-design.md](database-design.md).)

## Troubleshooting

- **A service container is unhealthy / restarting.** `docker compose logs -f <service>`.
  Most early-stage failures are a missing Flyway migration or the service starting
  before Postgres finishes initializing — the `depends_on: condition: service_healthy`
  should prevent the latter, but if you see connection-refused errors on first boot,
  re-run `docker compose up -d`.
- **Port already in use.** Something else on your machine is already bound to one of
  the ports in the table above; stop it or change the `ports:` mapping in
  `docker-compose.yml` (the container's internal port, matched to `server.port` in that
  service's `application.yml`, must stay the same).
- **Rebuilding after a code change.** `docker compose up -d --build <service>` rebuilds
  just that service's image; Maven dependency resolution inside the Docker build is
  cached via BuildKit's `--mount=type=cache,target=/root/.m2` in each `Dockerfile`, so
  repeat builds after a small code change are fast.
