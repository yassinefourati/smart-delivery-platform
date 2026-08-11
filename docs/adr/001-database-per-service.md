# ADR 001: Database-per-service

## Status
Accepted

## Context
Seven business services each own distinct data (identity, catalog, stock, orders,
payments, delivery, notifications). A shared database would let any service query any
other's tables directly, which is the easiest way to build this quickly and the surest
way to make every schema change a cross-team migration and every service's uptime
depend on every other service's query load.

## Decision
Each service owns an exclusive database (`user_db`, `product_db`, `inventory_db`,
`order_db`, `payment_db`, `delivery_db`, `notification_db`). No service is ever given
credentials to another service's database. Data needed across a boundary is obtained
through that owner's API or its published Kafka events, never SQL.

In local development, one Postgres *instance* hosts all seven databases (see
[docs/database-design.md](../database-design.md)) purely to avoid running seven
containers on a laptop — this is a deployment convenience, not a relaxation of the
boundary: each service's connection string and credentials are still scoped to just
its own database.

## Consequences
- No cross-service SQL joins, ever — reporting/analytics needs a dedicated read path
  (future `analytics-service`, out of scope for now) rather than querying multiple
  service databases directly.
- Data that's read often across a boundary (e.g. a product's name/price on an order
  line item) must be denormalized at the point it crosses, accepting some duplication
  in exchange for not coupling read paths at request time. See
  [database-design.md](../database-design.md#denormalization-at-boundaries).
- Multi-service "transactions" (e.g. reserve inventory *and* create an order
  atomically) cannot use a database transaction and must use a Saga instead — see
  [ADR 003](003-saga-pattern.md).
- Each service's Flyway migration history is independent, so schema evolution in one
  service never blocks or coordinates with another's release.
