-- Stuck-saga reaper support (ADR 008).
--
-- A saga that exhausts Spring Kafka's retries lands on a dead-letter topic and the order
-- is left in whichever resumable state it reached -- CREATED, INVENTORY_RESERVATION_PENDING,
-- INVENTORY_RESERVED or PAYMENT_PENDING -- with its stock held and nothing that would ever
-- look at it again. StuckSagaReaper finds those orders and either re-runs the saga or
-- gives up on it; saga_attempts is how it tells those two apart.
ALTER TABLE orders ADD COLUMN saga_attempts INT NOT NULL DEFAULT 0;

-- updated_at already exists (V1) and Hibernate's @UpdateTimestamp refreshes it on every
-- write, so a state transition -- or the reaper's own claim -- moves it. That is what
-- makes "older than saga.stuck-threshold" mean "nothing has happened to this order in a
-- while", and what gives the reaper's claim its lease: bumping updated_at takes the order
-- out of the eligible set until the threshold passes again.
--
-- Partial index because the eligible set is tiny and short-lived: in steady state almost
-- every row is in a terminal state the reaper never looks at.
CREATE INDEX idx_orders_stuck_saga ON orders (updated_at)
    WHERE status IN ('CREATED', 'INVENTORY_RESERVATION_PENDING', 'INVENTORY_RESERVED', 'PAYMENT_PENDING');
