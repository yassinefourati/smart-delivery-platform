# ADR 003: Saga pattern for order orchestration

## Status
Accepted

## Context
Placing an order is a multi-step workflow spanning four services (Order, Inventory,
Payment, and, once confirmed, Delivery), each with its own database
([ADR 001](001-database-per-service.md)). It needs to behave correctly as a whole — if
payment fails after inventory was reserved, the reservation must not be left dangling
— but there is no database transaction that can span four independently owned
databases without either a distributed-transaction coordinator (two-phase commit) or
a Saga.

Two-phase commit was considered and rejected: it requires every participant to support
XA transactions, forces services to hold locks for the duration of the slowest
participant's response, and makes the coordinator (Order Service, in this case) a
component with unusual authority over databases it doesn't own — precisely the coupling
[ADR 001](001-database-per-service.md) exists to avoid.

## Decision
Order placement is implemented as an **orchestration-style Saga**: Order Service holds
the workflow state (as `Order.status` — see [order-flow.md](../order-flow.md)) and
drives each step by calling the relevant service or reacting to its event; Inventory,
Payment, and Delivery each perform one local ACID transaction per step and know nothing
about the saga itself. Every step that can fail after an earlier step succeeded has an
explicit compensating action (release inventory, refund payment) rather than relying on
manual cleanup. Full sequence and compensation table in [saga.md](../saga.md).

## Consequences
- No two-phase commit, no XA — every service keeps ordinary local transactions.
- The system is only ever *eventually* consistent across services during an order's
  lifecycle — an order briefly exists as `INVENTORY_RESERVED` before payment is even
  attempted, and clients must poll/observe status rather than get one atomic
  "order placed" answer synchronously.
- Compensating actions must be idempotent, since the events/calls that trigger them can
  be retried (see [ADR 002](002-kafka-for-events.md)).
- All saga logic lives in Order Service. This was a deliberate concentration of
  complexity in one place, rather than spreading "does this event belong to a saga"
  logic across every participant — participants stay simple.
