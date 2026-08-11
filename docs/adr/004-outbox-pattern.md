# ADR 004: Transactional outbox

## Status
Accepted

## Context
Order Service (and any other service publishing events that must reliably reflect a
database change — see [ADR 002](002-kafka-for-events.md)) needs "save the state change"
and "publish the event announcing it" to happen atomically. If the database commit
succeeds but the Kafka publish fails (network blip, broker unavailable), the state
change is real but nobody downstream ever hears about it — the saga silently stalls. If
Kafka is published to first and the database commit then fails, consumers act on an
event whose cause never actually happened. A Java-level "commit DB, then send to
Kafka" with no coordination between the two has both failure windows.

## Decision
State-changing operations that must reliably announce themselves write an
`OutboxEvent` row (event envelope from [kafka-events.md](../kafka-events.md), plus a
`status` and timestamps) to the **same database, same transaction** as the business
data change. A separate publisher process polls unpublished outbox rows, publishes them
to Kafka, and marks them published — retrying on failure, and tolerating being killed
mid-publish, because "publish an already-published row again" is a safe duplicate (see
[ADR 002](002-kafka-for-events.md) — every consumer must already tolerate duplicates).

```
1. BEGIN
2. UPDATE order SET status = 'INVENTORY_RESERVED' ...
3. INSERT INTO outbox_event (event_type='InventoryReserved', payload=..., status='PENDING') ...
4. COMMIT
--- separate process, separate transaction ---
5. SELECT ... FROM outbox_event WHERE status = 'PENDING'
6. publish to Kafka
7. UPDATE outbox_event SET status = 'PUBLISHED' WHERE id = ...
```

## Consequences
- The database change and the fact that it happened are never inconsistent with each
  other — either both are committed, or neither is (ordinary local ACID transaction).
- Publishing is now *at-least-once* by construction (a crash between step 6 and 7
  republishes on restart) — consistent with, and reinforcing, the at-least-once
  consumer contract already required by [ADR 002](002-kafka-for-events.md).
- Adds an `outbox_event` table and a publisher process (poller, or Debezium-style CDC —
  a plain polling publisher is the starting implementation; log-based CDC is a possible
  later optimization, not required to get correctness) per service that needs it.
- End-to-end latency for an event to reach Kafka is bounded by the publisher's poll
  interval, not zero — an accepted trade-off for the reliability guarantee.
