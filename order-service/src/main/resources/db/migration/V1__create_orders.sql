-- user_id and shipping_address_id are opaque references to user-service, not foreign
-- keys -- order-service does not have (and must never take) direct database access to
-- user_db. See docs/database-design.md.
--
-- idempotency_key + idempotency_request_hash implement the Idempotency-Key contract
-- (docs section 16): UNIQUE (user_id, idempotency_key) means a retried "create order"
-- request for the same user and key finds this row instead of creating a duplicate;
-- the stored request hash lets OrderService detect and reject the same key being
-- reused for a materially different request instead of silently returning the wrong
-- order. See OrderService.
CREATE TABLE orders (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL,
    status                    VARCHAR(30) NOT NULL,
    shipping_address_id       UUID NOT NULL,
    total_amount              NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    idempotency_key           VARCHAR(255),
    idempotency_request_hash  VARCHAR(64),
    -- Optimistic lock: Phase 7's saga event handlers will mutate this row from
    -- multiple Kafka consumer threads; the same retry-on-conflict pattern used by
    -- inventory-service's reservation lifecycle applies here once that's wired up.
    version                   BIGINT NOT NULL DEFAULT 0,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_orders_user_id ON orders (user_id);
