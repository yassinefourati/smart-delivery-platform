# ADR 008: Reliable compensation, and a reaper for stuck sagas

## Status
Accepted

## Context
[ADR 003](003-saga-pattern.md) chose orchestrated sagas with explicit compensation, and
Phase 7 built them. Two ways for an order to end up permanently wrong survived that, and
both are the same shape: *something must happen next, and nothing owns making it happen.*

**Compensation on cancel was fire-and-forget.** `OrderController.cancel` called
`orderService.cancel(...)`, which committed the CANCELLED status, and then called
`sagaOrchestrator.compensateCancellation(...)` on the same request thread, outside that
transaction. The compensation itself caught every exception and logged it. So a refund
that failed, or a pod that died between the commit and the call, left an order that was
CANCELLED with its stock still reserved and its payment still taken — and nothing anywhere
that would ever look at it again. The customer sees a cancelled order; the warehouse sees
stock it cannot sell; the money never comes back.

The window was small, which is exactly what makes it the kind of bug that survives to
production and then happens a hundred times a day.

**A saga that exhausts its Kafka retries just stops.** A failing saga step is retried
three times and then dead-lettered (`KafkaConsumerConfig`), which correctly stops one
poison message from wedging a partition. But the *order* is left in whichever state it
reached — CREATED, INVENTORY_RESERVATION_PENDING, INVENTORY_RESERVED or PAYMENT_PENDING —
holding reservations forever. Nothing noticed, and nothing could: an order that is merely
*not progressing* is indistinguishable, from the outside, from one progressing slowly.
[saga.md](../saga.md) called resumability a property of the design; it was, but only for
sagas something eventually re-drives, and nothing re-drove these.

## Decision

### Compensation moves onto the event

`OrderService.cancel` writes the `order.cancelled` outbox row in the same transaction as
the cancellation — as it already did — and the payload gains `previousStatus`, a new,
purely additive field. `OrderCancellationListener` consumes that event and compensates.
The controller does not compensate at all any more.

This makes "the order was cancelled" and "something will compensate for it" one atomic
fact, and it gives compensation the bounded retry and dead-letter handling every other
consumer in the platform already has, instead of no retry at all.

`previousStatus` has to travel on the event because nothing else carries it: by the time
any consumer reads the order back it is already CANCELLED, and CANCELLED does not say
whether there is a reservation to release or a payment to refund.

**Compensation failures now throw.** They used to be caught and logged, which was the
only sensible thing to do on an HTTP thread with nothing to retry. On a listener it is
the opposite: throwing is what reaches the retry machinery. Every line is attempted before
anything is raised, so a retry has as little left to do as possible, and every step is
idempotent (inventory release is keyed by order+product, refund by order), so repeating
the ones that already succeeded costs nothing.

### A reaper for stuck sagas

`StuckSagaReaper` (`@Scheduled`) claims orders sitting in a resumable state that nothing
has touched for longer than `saga.stuck-threshold` (default 5m), and:

- under `saga.max-attempts` (default 3), re-runs `startSaga` — safe because every step is
  idempotent and it resumes from whatever the order's state says actually happened;
- at or over it, compensates and marks the order FAILED, announcing it on a new
  `order.failed` topic through the outbox.

**Claim, then act, with the claim as a lease.** Each run claims a batch in a short
transaction (`FOR UPDATE SKIP LOCKED`, exactly as the outbox poller does — [ADR
006](006-outbox-concurrency-and-ordering.md)) and does the slow part afterwards. The claim
increments `saga_attempts`, which Hibernate turns into a write, which refreshes
`updated_at` — so a claimed order leaves the eligible set for another
`stuck-threshold`. Two instances therefore cannot take the same order: one is skipped by
`SKIP LOCKED`, and by its next poll the order is no longer stuck.

This is deliberately *unlike* `OutboxPublisher`, which holds its transaction across its
sends. There the unit of work is one Kafka send and a rollback is free; here it is a saga
step that takes seconds and writes its own transactions as it goes, and wrapping that in
one outer transaction would collapse every intermediate commit into an all-or-nothing one.
A lease buys the same exclusivity without that.

**The state machine gains FAILED from every resumable state.** Previously only
INVENTORY_RESERVATION_PENDING could reach FAILED, so an order stalled in INVENTORY_RESERVED
had no terminal state to go to at all. An abandoned saga has to be able to end.

**Abandonment compensates unconditionally** rather than inferring what to undo from the
order's status. A stuck saga is stuck precisely because its recorded state may not match
what happened downstream: an order in INVENTORY_RESERVATION_PENDING may have reserved every
line, some of them, or none, and one in PAYMENT_PENDING may or may not have been charged.
Releasing a reservation that does not exist and refunding an order that was never charged
are both no-ops, so asking for both is strictly safer than guessing — and a 404 from
payment-service is read as "never charged", not as a failure.

## Consequences

- **The cancel API is unchanged.** Same request, same response, same status codes. What
  changed is that compensation now has an owner and a retry.
- **Compensation is no longer synchronous with the response.** A client that cancels and
  immediately asks inventory-service whether the stock is back may find it is not yet.
  This was already true in practice — compensation was best-effort and could simply fail —
  but it is now true by design, and bounded by the poll interval plus one round trip
  rather than unbounded.
- **A cancellation whose compensation can never succeed lands on `order.cancelled.DLT`**
  instead of vanishing into a log line. That is a queue a human can look at, which is the
  entire difference.
- **order-service now consumes its own `order.cancelled`**, in a consumer group of its own
  (`order-service-cancellation`) so it does not compete with the saga listeners' group.
- **A new `order.failed` topic**, consumed by notification-service. "We could not complete
  your order" is a different thing for a customer to read than "your cancellation went
  through", and the two were previously indistinguishable.
- **`saga.stuck-threshold` has to be longer than a healthy saga takes end to end**,
  including Spring Kafka's own three retries, or the reaper races the saga it is meant to
  rescue. Set too long, a stuck order holds stock for that much longer. Five minutes is
  comfortably past the former and well inside the latter for this platform.
- **A reaper that crashes mid-resolution strands that order for one lease period.** The
  next run picks it up, having burned one attempt on nothing. Acceptable: the alternative
  is distributed locks with liveness detection, which is a great deal of machinery for a
  case whose worst outcome is one wasted retry.
- **`saga.stuck.count` is now the signal that matters.** It should sit at or near zero.
  Anything else means sagas are stalling faster than the reaper resolves them — a condition
  no other metric on the dashboard would have shown.

## Alternatives considered

- **Keep compensating in the controller, but retry there.** Retrying in the request thread
  makes the client wait for something it does not care about, and still loses everything if
  the pod dies. The outbox row is the only thing that survives that, and it was already
  being written.
- **A transactional outbox row for the compensation itself** (a "compensation intent"
  table) rather than reusing the domain event. It would work, and it is what you would do
  if `order.cancelled` did not already exist and already carry everything needed. Adding a
  second mechanism to say the same thing is not worth the symmetry.
- **ShedLock for the reaper** instead of `FOR UPDATE SKIP LOCKED`. A cleaner-looking
  annotation, a new dependency, and it makes the reaper single-active: one instance works
  while the others idle. The claim query lets every instance work on disjoint orders, and
  the platform already uses exactly this pattern for the outbox — one idea, not two.
- **Resuming from a persisted saga log** (which steps completed) rather than re-deriving
  from the order's status. The status already is the log, because every step transitions it,
  and every step is idempotent so over-running one is free. A second record of the same
  thing is a second thing to get out of sync.
