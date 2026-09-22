-- Outbox concurrency, ordering, and retention (ADR 006), replacing the single-instance
-- assumptions the Phase 8 table was built under.
--
-- sequence_no replaces created_at as the poller's ordering key. created_at ties: rows
-- written in the same transaction share it to the microsecond routinely, and a tie
-- makes "the oldest PENDING row for this aggregate" ambiguous -- which is exactly the
-- question the claim query now has to answer unambiguously to keep a single
-- aggregate's events in order.
ALTER TABLE outbox_events ADD COLUMN sequence_no BIGINT;

-- Backfill deterministically rather than letting the sequence default assign values in
-- whatever order a table rewrite happens to visit rows: an existing PENDING backlog
-- keeps exactly the order the pre-Phase-15 poller would have published it in, so the
-- upgrade itself never reorders an aggregate's events.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id) AS rn FROM outbox_events
)
UPDATE outbox_events o SET sequence_no = ordered.rn FROM ordered WHERE o.id = ordered.id;

CREATE SEQUENCE outbox_events_sequence_no_seq OWNED BY outbox_events.sequence_no;
SELECT setval('outbox_events_sequence_no_seq',
              COALESCE((SELECT MAX(sequence_no) FROM outbox_events), 0) + 1,
              false);
ALTER TABLE outbox_events ALTER COLUMN sequence_no SET DEFAULT nextval('outbox_events_sequence_no_seq');
ALTER TABLE outbox_events ALTER COLUMN sequence_no SET NOT NULL;

-- Earliest time the poller may try a row again: now() for a fresh row (nothing to back
-- off from yet), pushed out exponentially by each failed publish so a poison row stops
-- costing a Kafka round trip every poll interval forever -- see OutboxEvent.
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- The claim query walks PENDING rows in sequence_no order, so the partial index that
-- used to be ordered by created_at is ordered by sequence_no instead. Same rationale as
-- before: in steady state this table is almost entirely PUBLISHED rows, which the
-- poller never looks at.
DROP INDEX idx_outbox_events_pending;
CREATE INDEX idx_outbox_events_pending ON outbox_events (sequence_no) WHERE status = 'PENDING';

-- For each candidate row the claim query asks "is there an older PENDING row for this
-- same aggregate?". That NOT EXISTS is the per-aggregate ordering guarantee, and it is
-- the one lookup that runs once per candidate, so it gets its own partial index.
CREATE INDEX idx_outbox_events_pending_aggregate
    ON outbox_events (aggregate_id, sequence_no) WHERE status = 'PENDING';

-- OutboxCleanupJob selects PUBLISHED rows by age. Not partial, unlike the two above:
-- this is the one query that deliberately targets the PUBLISHED majority.
CREATE INDEX idx_outbox_events_cleanup ON outbox_events (status, published_at);
