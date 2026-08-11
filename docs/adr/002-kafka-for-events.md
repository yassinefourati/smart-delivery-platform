# ADR 002: Kafka for asynchronous events

## Status
Accepted

## Context
Several facts need to reach more than one interested service without the producer
blocking on all of them, and without the producer needing to know who's currently
listening: an order being created matters to inventory and notifications; a payment
completing matters to order orchestration, delivery, and notifications. A
point-to-point mechanism (each producer calling each interested consumer's REST API)
would mean every producer maintains a growing list of consumers to call, and a
temporarily-down consumer causes either lost events or a retry storm the producer has
to implement itself.

## Decision
Business-significant state changes are published as events to Kafka topics (catalog in
[kafka-events.md](../kafka-events.md)). Producers publish once per topic; any number of
consumers can subscribe independently, and a consumer being down does not block or fail
the producer — messages are retained and delivered when the consumer recovers.

Kafka runs in KRaft mode (no ZooKeeper) — simpler operationally with no behavioral
trade-off relevant here, and it's where the project has settled as of the Kafka
versions available at the time this was written (see [local-development.md](../local-development.md)).

## Consequences
- Delivery is at-least-once, not exactly-once — every consumer must be idempotent (see
  [kafka-events.md](../kafka-events.md#delivery-semantics-and-consumer-requirements)).
  This is a real cost, accepted deliberately rather than papered over.
- Ordering is only guaranteed within a partition, so events are partitioned by the
  relevant aggregate ID (e.g. `orderId`) wherever order matters.
- Reliably publishing an event *and* committing the local database change that caused
  it needs its own solution — see [ADR 004](004-outbox-pattern.md).
- Operational surface area grows (a Kafka cluster to run, monitor, and reason about
  consumer lag for) in exchange for not building a bespoke pub/sub or retry system by
  hand.
