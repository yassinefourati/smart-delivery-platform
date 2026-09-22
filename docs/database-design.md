# Database Design

## Database-per-service

One PostgreSQL instance hosts one database per service in local development
(`user_db`, `product_db`, `inventory_db`, `order_db`, `payment_db`, `delivery_db`,
`notification_db` — created by
[`infrastructure/postgres/init-databases.sh`](../infrastructure/postgres/init-databases.sh)).
Sharing one Postgres *process* is a local-dev convenience to avoid running seven
containers; the databases are logically fully isolated, each service's Flyway history
lives only in its own database, and nothing prevents pointing each service at a
genuinely separate instance in a real deployment. See
[ADR 001](adr/001-database-per-service.md).

Each service manages its own schema with Flyway, versioned independently:

```
user-service/src/main/resources/db/migration/V1__create_users.sql
                                              V2__create_roles.sql
                                              V3__create_addresses.sql
product-service/src/main/resources/db/migration/V1__create_categories.sql
                                                 V2__create_products.sql
...
```

Hibernate's `ddl-auto` is disabled in every service (`spring.jpa.hibernate.ddl-auto:
validate`, once JPA is introduced) — schema changes only ever come from a reviewed
Flyway migration, never from the app inferring one at startup.

## Denormalization at boundaries

Because there is no cross-service join, any data a service needs from another service's
domain that it can't afford to fetch synchronously on every read is captured at the
point an event crosses the boundary:

- `OrderItem` (order-service) stores `productName` and `unitPrice` **at the time of
  purchase**, copied from Product Service's response when the order was created — not
  a foreign key into `product_db`. This is correct, not just convenient: an order's
  receipt must reflect the price paid, even if the product is later repriced or
  deleted.
- `Inventory` (inventory-service) stores `productId` as an opaque reference, not a
  foreign key — inventory-service has no way to enforce referential integrity against
  a database it doesn't own, and doesn't try to.

## Concurrency: preventing overselling

`inventory` rows carry `available_quantity`, `reserved_quantity`, and a `version`
column for optimistic locking (`@Version` in JPA). Two concurrent reservation requests
for the last unit of a product both read `available_quantity = 1`; only the transaction
that commits first succeeds, the second's `UPDATE ... WHERE version = ?` matches zero
rows, Hibernate raises `OptimisticLockException`, and the caller (order-service) sees a
failed reservation instead of silently overselling. Implemented and tested with
concurrent-access integration tests in Phase 4 (Inventory Service).

## Outbox tables

Every service that publishes Kafka events owns an `outbox_events` table in its own
database (order-service, inventory-service, payment-service since Phase 8;
delivery-service from the start, Phase 9) — a business-data write and the outbox row
announcing it commit in the same local transaction. See [ADR 004](adr/004-outbox-pattern.md)
and [kafka-events.md](kafka-events.md#the-outbox-in-practice) for the full design.

Phase 15 added three columns and rebuilt the indexes on all four of those tables
([ADR 006](adr/006-outbox-concurrency-and-ordering.md)):

| Column | Why |
|---|---|
| `sequence_no BIGINT NOT NULL` | Database-assigned publish order. Replaces `created_at` as the poller's ordering key, because rows written in one transaction share a `created_at` and a tie makes "the oldest unpublished event for this aggregate" ambiguous. |
| `next_attempt_at TIMESTAMPTZ NOT NULL` | Earliest time the poller may retry a failed row, pushed out exponentially per attempt. |

The index on `(created_at) WHERE status = 'PENDING'` became one on
`(sequence_no) WHERE status = 'PENDING'`, joined by
`(aggregate_id, sequence_no) WHERE status = 'PENDING'` for the claim query's
oldest-per-aggregate check and by `(status, published_at)` for the retention job — the
one query here that deliberately targets the `PUBLISHED` majority rather than avoiding
it. Existing rows are backfilled in `created_at` order rather than left to a table
rewrite's arbitrary order, so an in-flight `PENDING` backlog is not reordered by the
migration itself.

## Stuck-saga bookkeeping (Phase 17)

`orders` gained one column, `saga_attempts INT NOT NULL DEFAULT 0`
([ADR 008](adr/008-reliable-compensation-and-stuck-saga-reaper.md)). `updated_at` already
existed and is maintained by Hibernate's `@UpdateTimestamp` on every write, so a state
transition moves it — which is what lets "untouched for longer than
`saga.stuck-threshold`" mean "this saga has stalled".

Incrementing `saga_attempts` is also how `StuckSagaReaper` takes out its lease: the
increment is a write, the write refreshes `updated_at`, and the order therefore leaves the
eligible set until the threshold passes again. One column serves both the retry budget and
the concurrency control.

The supporting index is partial, because in steady state almost every row is in a terminal
state the reaper never looks at:

```sql
CREATE INDEX idx_orders_stuck_saga ON orders (updated_at)
    WHERE status IN ('CREATED', 'INVENTORY_RESERVATION_PENDING', 'INVENTORY_RESERVED', 'PAYMENT_PENDING');
```

## Migration ownership

A service's Flyway migrations are that service's alone to write and run — no
migration in `order-service` ever touches `inventory_db`, and CI runs each service's
migrations against only its own throwaway test database (Testcontainers), never a
shared one.
