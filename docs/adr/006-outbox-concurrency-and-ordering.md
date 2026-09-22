# ADR 006: Outbox concurrency, ordering, and retention

## Status
Accepted

## Context
[ADR 004](004-outbox-pattern.md) made "the business change committed" and "the event
announcing it exists" atomic, and Phase 8 implemented it: an `outbox_events` table per
publishing service, and a `@Scheduled` `OutboxPublisher` that reads `PENDING` rows
oldest-first, sends each to Kafka, and marks it `PUBLISHED`.

That poller was only correct with exactly one instance of the service running — an
assumption nothing in the design, the configuration, or the deployment enforces, and one
that stops holding the first time a service is scaled out. Three distinct problems:

**Two instances publish the same row.** `SELECT ... WHERE status = 'PENDING'` takes no
lock, so two replicas polling two seconds apart read the same rows and both send them.
Consumers must tolerate duplicates (ADR 002), so nothing is *corrupted* by this — but it
doubles broker traffic for no reason, and it makes the duplicate rate a function of how
many replicas are running rather than of anything actually going wrong.

**Per-aggregate ordering is not guaranteed, even with one instance.** The publish loop
continued after a failed send, so if event 1 for order X failed and event 2 succeeded, a
consumer saw X's events out of order — `OrderCancelled` before `OrderCreated`, for
instance. With more than one instance it is worse: two replicas can each claim a
different event of the same aggregate and publish them in either order. Kafka's ordering
guarantee is per partition, and events are keyed by aggregate id, so Kafka itself will
preserve whatever order the producer *sends* in; nothing was preserving that order on
the way in.

`created_at` is not a usable ordering key either. Rows written in the same transaction
routinely share it, and a tie makes "the oldest unpublished event for this aggregate"
ambiguous.

**The table grows forever.** Nothing ever deleted a `PUBLISHED` row. Every event every
service had ever published stayed in its database indefinitely, slowing the poller's own
index scans and bloating backups.

A fourth, smaller problem: a row that could never be published (an unroutable topic, a
payload the broker rejects) was retried every two seconds forever, competing with
healthy rows for every batch.

## Decision

**A monotonic `sequence_no`**, assigned by a database sequence at insert time, is the
publish order — not `created_at`. It cannot tie, and it is assigned by the database
rather than by whichever instance built the row.

**Claiming is an atomic, locking query** run inside the publisher's own transaction:

```sql
SELECT * FROM outbox_events o
WHERE o.status = 'PENDING'
  AND o.next_attempt_at <= :now
  AND NOT EXISTS (
      SELECT 1 FROM outbox_events e
      WHERE e.aggregate_id = o.aggregate_id
        AND e.status = 'PENDING'
        AND e.sequence_no < o.sequence_no)
ORDER BY o.sequence_no
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

Two clauses carry the whole design:

- `FOR UPDATE SKIP LOCKED` makes the claim exclusive. Rows another instance is already
  working on are invisible rather than published a second time.
- The `NOT EXISTS` restricts the result to the **oldest unpublished row per aggregate**.
  A later event can never be claimed — by this instance or any other — while an earlier
  one for the same aggregate is still unpublished, whether it is unpublished because a
  peer holds its lock, because its send failed, or because it is backing off. That is
  the per-aggregate ordering guarantee, and it holds for any number of instances without
  any coordination between them.

`next_attempt_at` is deliberately *not* part of the `NOT EXISTS`: a backing-off row must
still block its aggregate's later events. Skipping past it would be exactly the
reordering this fixes.

**The claiming transaction spans the sends**, rather than claiming, committing, and
sending afterward. Holding the row locks for the whole batch is what makes "claimed by
this instance" mean anything without inventing a third `IN_PROGRESS` status and a reaper
for rows orphaned by a crash: an instance that dies mid-batch rolls back, and its rows
are ordinary `PENDING` rows again for whoever polls next.

**Failed rows back off exponentially** (`next_attempt_at`, `outbox.initial-backoff`
doubling to `outbox.max-backoff`). Retries stay **unbounded**, as ADR 004 requires —
abandoning a row would silently drop an event whose business change is already
committed, the one outcome the outbox exists to prevent. The cap only makes a poison row
cheap instead of giving up on it.

**`OutboxCleanupJob` deletes `PUBLISHED` rows older than `outbox.retention`** (default
7 days), in bounded batches, each `DELETE ... WHERE id IN (SELECT ... FOR UPDATE SKIP
LOCKED)` so several instances can run it at once without blocking each other. `PENDING`
rows are never deleted at any age: age there means the event is *stuck*, and deleting it
would turn a visible backlog into silent event loss.

## Consequences

- **Ordering is now a guarantee, not an accident**: per aggregate, at least once, in
  publish order — see [kafka-events.md](../kafka-events.md#delivery-semantics-and-consumer-requirements).
  Consumers still must tolerate duplicates; they no longer have to tolerate reordering
  within one aggregate.
- **Throughput per aggregate is capped at one event per poll interval.** This is the
  direct cost of the ordering guarantee, and it is the right trade here: an order emits a
  handful of events over its lifetime, not a stream. A deployment that needs more would
  claim several rows per aggregate and keep the ordering in the publish loop, which the
  loop already implements and tests.
- **A database transaction is held open across network I/O**, bounded by
  `outbox.batch-size` × the 5s per-send timeout. Tune the batch size down where that
  bound is too loose. The alternative — a claimed status plus a reaper for orphaned rows
  — buys shorter transactions at the cost of a second failure mode to get right, and was
  not worth it at this scale.
- **A stuck aggregate is now visible.** `outbox.pending.count` and
  `outbox.oldest.pending.age.seconds` (per service, on the Grafana dashboard) make a
  backlog observable; before this phase nothing distinguished "publishing normally" from
  "publishing nothing at all".
- **Four services carry an identical copy of all of this.** `OutboxEvent`,
  `OutboxEventRepository`, `OutboxPublisher`, `OutboxCleanupJob`, `OutboxProperties`, and
  `OutboxMetrics` are byte-for-byte identical across order-, inventory-, payment-, and
  delivery-service apart from their package declaration. That was tolerable when the
  publisher was thirty lines; it is the main argument for extracting a shared platform
  starter, which is what the next phase weighs. Note that this is *infrastructure*
  duplication, not the deliberate event-contract duplication ADR 002 argues for — the
  reasons that keep `EventEnvelope` per-service do not apply to a poller.

## Alternatives considered

- **A distributed lock (ShedLock, or a Postgres advisory lock) around the whole poll.**
  Simple, and it does prevent double publishing — by making every instance but one idle.
  `FOR UPDATE SKIP LOCKED` lets every instance do useful work on disjoint rows, which is
  the point of running more than one.
- **Log-based CDC (Debezium) instead of a poller.** Still the right long-term answer, and
  ADR 004 already names it as a possible optimization. It would also solve ordering, since
  the WAL is inherently ordered. It is a whole new piece of infrastructure to run, and
  this phase is about making the existing poller correct, not replacing it.
- **Kafka's idempotent producer / transactions.** Solves duplicate *delivery* from
  producer retries, which is not the problem here: the duplicates came from two instances
  reading the same row, and the reordering came from the application's own send order.
