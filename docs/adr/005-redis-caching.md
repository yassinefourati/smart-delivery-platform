# ADR 005: Redis for product caching

## Status
Accepted

## Context
Product catalog reads (browse/search/filter) are the highest-volume, most
read-skewed traffic in the system — far more reads than writes, and the same popular
products are fetched repeatedly. Hitting Postgres for every read is unnecessary load
for data that changes relatively rarely compared to how often it's read.

## Decision
`product-service` caches individual product reads (and, where practical, common
list/search results) in Redis, keyed so that a product update can precisely invalidate
just that product's cache entry rather than flushing the whole cache. `product-service`
is the only writer to its cache entries — no other service reaches into Redis on
product-service's behalf, keeping cache invalidation the sole responsibility of the
service that owns the underlying data (consistent with
[service-boundaries.md](../service-boundaries.md)).

Redis is explicitly **not** used as a system of record for anything. It is disposable:
if the Redis container is wiped, every service continues to function correctly (slower,
until the cache warms) purely from Postgres. This rules out patterns like storing a
reservation or an order's working state in Redis — those stay in Postgres.

## Consequences
- Product reads are fast and don't scale-couple to Postgres connection limits under
  read-heavy load.
- Every product mutation (create/update/delete) must invalidate the affected cache
  entries in the same request, or reads become stale in a way customers would notice
  (seeing an out-of-stock or repriced product's old data). This is implemented, not
  left implicit, when Phase 3 adds the caching layer.
- Added operational component (Redis) whose failure must degrade gracefully — reads
  fall through to Postgres on a cache miss or Redis unavailability rather than erroring
  the request.
- Other stated future uses (rate limiting, short-lived idempotency records, distributed
  locks) follow the same rule: Redis holds derived/ephemeral state only, never the
  only copy of something that must survive a cache flush.
