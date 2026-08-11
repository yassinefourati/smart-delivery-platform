-- order_id is an opaque reference to order-service, not a foreign key --
-- payment-service does not have (and must never take) direct database access to
-- order_db. See docs/database-design.md.
--
-- UNIQUE (order_id): one Payment per order -- a charge is requested at most once per
-- order (idempotent per orderId, mirroring inventory-service's reservation idempotency);
-- a later refund is a new PaymentTransaction against this same row, not a new Payment.
CREATE TABLE payments (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id   UUID NOT NULL UNIQUE,
    amount     NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency   VARCHAR(3) NOT NULL,
    status     VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
