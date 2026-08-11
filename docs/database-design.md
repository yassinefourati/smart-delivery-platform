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

Any service that publishes Kafka events from a transaction that also changes its own
data owns an `outbox_event` table in its own database (order-service being the primary
example, given the saga). See [ADR 004](adr/004-outbox-pattern.md) and
[kafka-events.md](kafka-events.md) for the full design; the table shape is finalized
when Phase 8 implements it.

## Migration ownership

A service's Flyway migrations are that service's alone to write and run — no
migration in `order-service` ever touches `inventory_db`, and CI runs each service's
migrations against only its own throwaway test database (Testcontainers), never a
shared one.
