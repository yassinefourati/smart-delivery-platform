# Kafka Event Catalog

> As of Phase 9, every topic in the catalog below has a real producer and every
> consumer order-service has carried since Phase 6 finally has something to react to:
> `order.created`/`order.cancelled` (order-service), `inventory.reserved`/
> `inventory.released`/`inventory.failed` (inventory-service), `payment.completed`/
> `payment.failed` (payment-service), and `shipment.created`/`delivery.assigned`/
> `delivery.completed` (delivery-service, consuming `payment.completed` to create the
> shipment that starts its own side of the flow). All of it includes bounded retry +
> dead-letter handling, and every producer publishes through the transactional outbox
> (ADR 004) -- see [below](#the-outbox-in-practice). As of Phase 10, notification-service
> consumes all 10 topics too -- the platform's other consumer, alongside order-service,
> but the only one with no events of its own to publish. See [saga.md](saga.md) for the
> full picture of what drives an order through its lifecycle end to end.

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

**`shipment.created`/`delivery.assigned`/`delivery.completed` are real as of Phase 9.**
delivery-service consumes `payment.completed` (`PaymentCompletedListener`) to create a
`Shipment` per order, publishes `shipment.created`; an admin assigning a `DeliveryAgent`
publishes `delivery.assigned`; that agent marking the delivery done publishes
`delivery.completed`. order-service's handling of all three has been real and tested
since Phase 6 (`OrderSagaEventHandler`) -- Phase 9 only had to make delivery-service
actually publish them, no order-service change was needed.

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
  notification-service (Phase 10) is the one deliberate exception: it has no state to
  make idempotent in the first place (it only logs), and a duplicated log line has none
  of the correctness consequences a duplicated charge or shipment would have -- see
  `NotificationSender`'s Javadoc.
- **BigDecimal fields inside `payload`**: a monetary value round-tripped through the
  envelope's untyped `payload` (`JsonNode`) -- POJO to tree to JSON text to tree to POJO,
  the path every producer/consumer here uses -- is not guaranteed to keep its original
  scale (Jackson may normalize `50.00` to a differently-scaled equivalent that's still
  numerically 50, just no longer prints as `"50.00"`). The value itself is never wrong,
  only its default `toString()`. Found in Phase 10 when notification-service became the
  first consumer to actually render a payload's monetary field instead of just reading
  an id off it (`OrderCreatedPayload.totalAmount`, `PaymentCompletedPayload.amount`).
  Any consumer rendering one of these for a human should format it explicitly (`%.2f`,
  not a bare `%s` on the `BigDecimal`) rather than assume the string form survived
  intact -- see `NotificationEventListener`'s Javadoc.
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

## The outbox in practice

**Every producer in this catalog goes through the transactional outbox** (ADR 004)
instead of calling `KafkaTemplate.send()` directly -- order-service, inventory-service,
and payment-service since Phase 8; delivery-service used it from the day its first
producer (`DeliveryEventPublisher`) was written, Phase 9, rather than repeating the
direct-`send()` gap Phase 8 had just finished closing elsewhere. Each service has its
own `outbox_events` table (`OutboxEvent`/`OutboxStatus`/`OutboxEventRepository`) and:

- `OrderEventPublisher`, `InventoryEventPublisher`, `PaymentEventPublisher`,
  `DeliveryEventPublisher` don't touch `KafkaTemplate` at all. Instead they build the
  same `EventEnvelope` as before,
  serialize it, and write it as a `PENDING` `OutboxEvent` row -- called from *inside*
  the same `@Transactional` method that made the business change (e.g.
  `OrderService.create`, `InventoryReservationOperations.reserveAttempt`,
  `PaymentService.charge`), so the row and the change either both commit or neither
  does.
- A separate `OutboxPublisher` (`@Scheduled`, default every 2s --
  `outbox.poll-interval-ms`) polls `PENDING` rows oldest-first, sends each to Kafka, and
  marks it `PUBLISHED` on success. A failed send is left `PENDING` (with `attempts`/
  `lastError` updated) for the next poll to retry -- indefinitely, since "publish an
  already-published row again" is the only failure mode this needs to tolerate, and
  every consumer here already handles duplicates (see above).
- This closes the gap the pre-Phase-8 design had: a crash between the DB commit and the
  Kafka publish used to lose the event silently. Now the event is durably recorded in
  the same transaction as the fact it describes, and only *when* it reaches Kafka is
  variable (bounded by the poll interval), not *whether* it eventually does.

One deliberate behavior change worth calling out: publishing now happens only on the
actual state-change path, not on every idempotent replay. Pre-Phase-8,
`InventoryReservationService.reserve()` re-published `InventoryReserved` on every call
for an order+product that was already reserved, since publish always ran after retrying
succeeded, whichever branch that was. Post-Phase-8, `InventoryReservationOperations`
writes the outbox row only in the same transaction as an actual new reservation --
the early-return "already reserved" branch doesn't re-announce an outcome that was
already announced. This is more correct, not just different: consumers were always
required to tolerate duplicates, but not emitting a duplicate in the first place when
nothing changed is strictly better.

See [saga.md](saga.md#relationship-to-the-outbox-pattern) for how this changed the
saga's publish path specifically, and [ADR 004](adr/004-outbox-pattern.md) for the full
design rationale.
