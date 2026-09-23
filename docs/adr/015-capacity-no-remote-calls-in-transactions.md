# ADR 015: No remote calls inside a transaction; pools and partitions sized for the topology

## Status
Accepted (Phase 23)

## Context

Reading the production configuration for "would this survive a stress test?" turned up
three limits. Each was invisible in functional tests, and together they cap order
throughput far below what the hardware would allow. A load test then measured the first
one ([docs/load-testing.md](../load-testing.md)).

1. **A remote call held a database connection.** `OrderService.create` priced each order
   line by calling product-service **inside** the order's transaction. So every in-flight
   create held one pooled connection for as long as product-service took to answer, and
   with retries (3 attempts, 3s read timeout, backoff) that is up to about 10 seconds.

   The production pool was 4 connections per pod, and that pool is shared with everything
   else in order-service that touches the database: status reads, the saga's Kafka
   listeners, the outbox publisher. One slow dependency therefore took down every database
   path in the service, all failing with 503 at the 2-second pool timeout, including paths
   that never call product-service.

   With 1.5s of latency injected in front of product-service, at 10 requests/s:
   - 12% of order-status reads failed, with p95 at exactly 2.0s, the pool timeout;
   - 10% of order creates were rejected;
   - the saga's p95 time to PAID went from 2.6s to 27.6s;
   - 5% of sagas never finished within a minute.
2. **Pools sized for a topology that no longer existed.** `poolMax: 4` was chosen so that
   all six databases on ONE Postgres instance fit a stock `max_connections` of 100. Phase
   22 gave every service its own CloudNativePG cluster, but the constraint stayed. 4
   connections per pod was still a limit on order-service, with nothing left to justify
   it, and it was the first resource that finding 1 exhausted.
3. **One partition, one consumer thread.** The applications never created their topics,
   so they got the broker's default partition count (1 on Compose, and on many managed
   brokers). Listener concurrency was Spring's default of 1. A consumer group can never
   have more active consumers than partitions, so every saga step ran through one thread
   per topic, platform-wide, however many pods were running.

   The dead-letter resolvers had a matching trap. They published to the **same partition
   number** on the `.DLT` topic, which only works while the DLT has at least as many
   partitions as its source. delivery-service also still used Spring's `-dlt` suffix
   instead of the documented `.DLT`.

## Decision

1. **No remote call runs while a transaction is open.** `OrderService.create` now has three
   steps:
   1. An idempotent-replay check, in its own short read-only transaction. A retry is
      therefore still answered while product-service is down.
   2. Pricing, with no transaction open.
   3. A transaction that only re-checks the key, inserts and writes the outbox row.

   The saga orchestrator already followed this rule (`startSaga` is deliberately not
   `@Transactional`); this makes it a rule of the codebase. Two tests enforce it:
   - `OrderServiceTest` asserts that the product lookup sees no active transaction and
     the insert does;
   - `OrderCreatePoolIsolationIntegrationTest` runs with a ONE-connection pool, parks a
     create inside a product lookup that doesn't answer, and requires a status read to
     succeed meanwhile. It fails on the old code.
2. **Pools are sized per topology.** `database.topology` is `shared` (one instance: the sum
   of all services must fit) or `per-service` (each service's `maxReplicas × poolMax` must
   fit its own cluster's app-role limit). NOTES.txt does the matching arithmetic.
   Production is `per-service` with `poolMax: 10`; the worst case is product-service at
   8 × 10 = 80, against sdp-data's `appConnectionLimit` of 120.
3. **Producers declare the topics they own, with enough partitions to use.**
   - platform-starter's `OwnedTopics` turns a service's `KafkaTopics` names into Spring
     Kafka `NewTopics`. It's controlled by `kafka.topics.create/partitions/replicas` and
     is off by default, so no broker-less test context waits on an admin connection.
     Spring Kafka creates missing topics and raises the partition count of existing ones.
   - Consumers run `spring.kafka.listener.concurrency` threads.
   - Production: 6 partitions and 3 threads × 2 order-service pods, so every consumer
     group has one thread per partition.
   - Events are keyed by aggregate id, so one order's events stay on one partition, in
     order.
   - Dead letters go to `<topic>.DLT` with partition `-1`, so the producer picks the
     partition from the key, which works whatever each topic's partition count is.
4. **Capacity is observable and testable.**
   - `order.create.pricing`, a histogram timer, plus the alerts `SdpDbPoolSaturated`
     (requests waiting for a connection) and `SdpOrderPricingSlow`.
   - A k6 suite (`load/sdp-load.js`: smoke, load, stress, spike and soak profiles, plus
     a probe that follows orders to PAID), and a manually triggered workflow that runs it
     against Compose.

## Consequences

- **A slow product-service now makes order placement slow, not order-service unavailable.**
  Status reads, the saga and the outbox keep their connections. Creates are bounded by the
  product-service bulkhead (20 concurrent per pod) instead of by the pool.
- **Pricing and insert are no longer atomic with respect to product data.** A price can
  change between the lookup and the insert. That was already true in effect, since the
  lookup read another service's database at a moment unrelated to this transaction. The
  order records the price it was shown, as before.
- **A retry with a known key is answered before pricing.** It no longer fails when
  product-service is down, which is strictly better.
- **More threads and connections per pod.**
  - Listener concurrency multiplies consumer threads by the number of `@KafkaListener`
    methods (order-service has nine).
  - Each thread uses a connection only for the short transactions a record needs.
  - The pool-saturation alert tells you when that stops being true.
- **Partition count is a one-way decision.** Kafka can add partitions but never remove
  them. Adding them also changes which partition a given key maps to, so plan it with
  consumers drained, or accept a brief reordering between *different* orders' events,
  which the saga tolerates. One order's events stay ordered either way once the change
  settles.
- **Topic creation needs admin permission on the broker.** Where infrastructure-as-code
  owns topics, `kafka.topics.create` stays off and the same partition count goes into
  that code.

## Alternatives considered

- **A larger pool alone.** That only raises the threshold. A slow dependency still holds
  every connection eventually; it just takes more of them.
- **Caching product prices in order-service.** It would remove the call from the hot
  path, but it duplicates product-service's data, needs invalidation, and doesn't fix the
  general rule. It's worth doing later for latency, not as the fix for starvation.
- **Asynchronous pricing (accept the order, price it in the saga).** This would change the
  API contract: the `201` response currently carries the priced total. That's too large
  a change for a capacity fix.
- **Autoscaling order-service.** It's still blocked by the canary Rollout, whose
  autoscaler would target the scaled-to-zero Deployment; a follow-up can point it at the
  Rollout. More pods of a design that starves its own pool would have starved the same
  way.

## Follow-ups (not in this phase)

- **A rate limit on login.** `POST /api/v1/auth/login` costs a BCrypt verification
  (strength 10) and nothing limits it, per client or overall. A login storm is a CPU
  problem for user-service, and credential stuffing costs the attacker nothing. The
  gateway's `RequestRateLimiter` needs Redis, which the gateway doesn't have yet.
- **Autoscaling order-service.** Point the HPA at the Argo Rollout instead of the
  Deployment. See Alternatives.
- **Fewer saga round trips.** `startSaga` runs on the consumer thread that took
  `order.created`. For each order line it makes a reserve call and, after the charge, a
  deduct call to inventory-service. When inventory-service is slow, those calls hold
  that thread (no longer a connection), so saga throughput is threads ÷ time per saga.
  One reserve and one deduct call per order, instead of per line, would cut the time a
  multi-line order holds the thread.
- **A load test in staging** with production values and replication. See
  [load-testing.md](../load-testing.md#what-this-does-not-cover).
