# Kafka Event Catalog

> Producers and consumers for `order.created`/`order.cancelled` (order-service),
> `inventory.reserved`/`inventory.released`/`inventory.failed` (inventory-service), and
> `payment.completed`/`payment.failed` (payment-service) are all wired and real as of
> Phase 7, including retry + dead-letter handling. `shipment.created`,
> `delivery.assigned`, and `delivery.completed` are consumed by order-service already
> (so its saga-handling code is real and tested), but nothing publishes them yet --
> delivery-service doesn't exist until Phase 9. See [saga.md](saga.md) for the full
> picture of what drives an order through its lifecycle today versus what's still
> missing.

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
| `payment.completed` | payment-service | order-service, delivery-service, notification-service | orderId, paymentId, amount |
| `payment.failed` | payment-service | order-service, notification-service | orderId, reason |
| `shipment.created` | delivery-service | order-service, notification-service | orderId, shipmentId |
| `delivery.assigned` | delivery-service | order-service, notification-service | orderId, shipmentId, agentId |
| `delivery.completed` | delivery-service | order-service, notification-service | orderId, shipmentId, deliveredAt |

`delivery.assigned`'s payload includes `orderId` (not just `shipmentId`/`agentId`) so that
order-service -- which only knows about shipments by their effect on an order, not as a
concept of its own -- doesn't need a synchronous call back to delivery-service just to
know which order to advance. Same self-contained-payload reasoning as `OrderCreated`.

**No `payment.requested` topic.** The original design (this catalog, pre-Phase-7)
sketched order-service publishing a `payment.requested` event for payment-service to
consume. The actual implementation asks for a charge the same way it asks
inventory-service to reserve stock: a direct, synchronous REST call
(`PaymentServiceClient`) — because the saga orchestrator needs the outcome immediately
to decide whether to proceed or compensate, and a fire-and-forget event doesn't give it
that. See [service-boundaries.md](service-boundaries.md)'s communication matrix and
[saga.md](saga.md).

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
- **Retry and dead-letter**: a listener that throws is retried 3 times, 1 second apart
  (Spring Kafka's `DefaultErrorHandler` with a `FixedBackOff`); once exhausted, the
  message is published to a `<topic>.DLT` dead-letter topic (`DeadLetterPublishingRecoverer`)
  instead of blocking the partition forever or being silently dropped. A message that
  can never be parsed hits this same path -- see `OrderSagaEventListener`.

## Why the envelope is duplicated per service, not shared

Every publishing/consuming service defines its own local `EventEnvelope` (and payload
records) rather than importing one from a shared library -- consistent with this
codebase having no shared domain module (see [architecture.md](architecture.md)). What
producer and consumer actually have to agree on is the JSON shape documented on this
page, not a Java type. This matters concretely: Spring Kafka's default JSON
(de)serialization support for generics adds a `__TypeId__` header naming the
*producer's* Java class, which does not exist on a different service's classpath.
Every producer/consumer here uses plain `String` (de)serializers and parses the
envelope's `payload` as a `JsonNode`, sidestepping that entirely -- see
`OrderEventPublisher` / `OrderSagaEventListener` / `InventoryEventPublisher`.

## Relationship to the outbox pattern

**Current state (as of Phase 7): not yet using the outbox.** `OrderEventPublisher`,
`InventoryEventPublisher`, and `PaymentEventPublisher` all call `KafkaTemplate.send()`
directly, after the owning transaction has already committed. This is a known, flagged gap, not an oversight --
see [ADR 004](adr/004-outbox-pattern.md) for exactly why a bare `send()` isn't safe (a
crash between commit and publish loses the event silently) and
[saga.md](saga.md#relationship-to-the-outbox-pattern) for how the outbox and the saga
fit together once it's added. Phase 8 replaces these direct sends with an outbox write
in the same transaction as the business change, for the producers where losing an
event actually matters to correctness. Until then: the underlying database change is
never at risk (it's already committed by the time publishing is attempted), only the
event announcing it is.
