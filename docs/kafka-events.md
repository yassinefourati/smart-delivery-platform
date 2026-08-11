# Kafka Event Catalog

> Topics and producers/consumers are wired starting in Phase 6. This document fixes the
> event envelope and topic list before that phase starts, so every service implements
> the same contract.

## Envelope

Every event published to Kafka uses the same envelope, with the event-specific data in
`payload`:

```json
{
  "eventId": "b3b5a5b0-1e0e-4b8a-9f3a-8e6a2e9b6a11",
  "eventType": "OrderCreated",
  "eventVersion": 1,
  "timestamp": "2026-08-11T12:00:00Z",
  "correlationId": "5b1a7e2e-9c3d-4a2b-8f1e-2d6c9a0b1234",
  "source": "order-service",
  "payload": { }
}
```

| Field | Purpose |
|---|---|
| `eventId` | Unique per publish attempt. Consumers use it (or a business key inside the payload — see [saga.md](saga.md#idempotency-and-duplicate-events)) to detect and drop duplicates delivered by at-least-once semantics. |
| `eventType` | Discriminator for deserialization/routing. |
| `eventVersion` | Bumped on breaking payload changes; consumers can support N and N-1 during a rollout. |
| `timestamp` | When the source service produced the event (not when Kafka appended it). |
| `correlationId` | Propagated from the originating HTTP request (gateway-assigned) through every event caused by it, so a whole order's flow can be traced across services and topics. See [observability.md](observability.md). |
| `source` | Producing service name, for debugging and dead-letter triage. |
| `payload` | Event-specific data — always enough for consumers to act without an extra synchronous call back to the producer, per the design goal below. |

## Design goal: self-contained payloads

A consumer should not need to call the producing service's API just to act on its
event. `OrderCreated`'s payload therefore includes the order's line items (product ID,
quantity, unit price at time of order), not just an `orderId` the consumer would have
to look up. This trades a larger payload for one fewer synchronous dependency at
consume time — the right trade here, since consumers (Inventory, Notification) reacting
to a producer that might be down is exactly the failure mode Kafka is meant to avoid
reintroducing.

## Topics

| Topic | Producer | Consumers | Payload highlights |
|---|---|---|---|
| `order.created` | order-service | inventory-service, notification-service | orderId, userId, items[], totalAmount |
| `order.cancelled` | order-service | inventory-service, delivery-service, notification-service | orderId, reason |
| `inventory.reserved` | inventory-service | order-service | orderId, reservationId |
| `inventory.released` | inventory-service | order-service | orderId, reservationId |
| `inventory.failed` | inventory-service | order-service, notification-service | orderId, reason (e.g. `INSUFFICIENT_STOCK`) |
| `payment.requested` | order-service | payment-service | orderId, amount, currency |
| `payment.completed` | payment-service | order-service, delivery-service, notification-service | orderId, paymentId, amount |
| `payment.failed` | payment-service | order-service, notification-service | orderId, reason |
| `shipment.created` | delivery-service | order-service, notification-service | orderId, shipmentId |
| `delivery.assigned` | delivery-service | notification-service | shipmentId, agentId |
| `delivery.completed` | delivery-service | order-service, notification-service | orderId, shipmentId, deliveredAt |

## Delivery semantics and consumer requirements

Kafka is configured for **at-least-once** delivery — not exactly-once at the
application level (see the master engineering brief, section 15: "do not assume Kafka
delivery is exactly-once at the application level"). Consequences every consumer must
handle:

- **Duplicates**: the same event may be delivered more than once (producer retry after
  an unacknowledged send, consumer rebalance replaying an uncommitted offset). Every
  consumer's handler must be idempotent — either by checking `eventId` against a
  processed-events record, or, more simply, because the state transition it performs is
  naturally idempotent (see [saga.md](saga.md#idempotency-and-duplicate-events)).
- **Ordering**: ordering is only guaranteed within a partition. Events are partitioned
  by `orderId` (or the relevant aggregate ID) so that all events for one order are
  strictly ordered relative to each other, while different orders can be processed in
  parallel across partitions.
- **Retry and dead-letter**: consumer failures are retried with backoff (Spring Kafka's
  `DefaultErrorHandler`); after exhausting retries, the message is published to a
  `<topic>.DLT` dead-letter topic instead of blocking the partition forever or being
  silently dropped. Wired in Phase 6.

## Relationship to the outbox pattern

Events are never published directly from request-handling code with a bare
`kafkaTemplate.send(...)` in the middle of a `@Transactional` method — see
[ADR 004](adr/004-outbox-pattern.md) for why that's unsafe, and [saga.md](saga.md#relationship-to-the-outbox-pattern)
for how the outbox and the saga fit together.
