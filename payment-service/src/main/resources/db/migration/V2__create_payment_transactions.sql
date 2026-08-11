-- One row per attempt against a Payment (a charge, later possibly a refund) -- distinct
-- from the Payment row itself, which is the durable "this order was charged $X" record.
CREATE TABLE payment_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id          UUID NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    type                VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL,
    provider_reference  VARCHAR(64) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_transactions_payment_id ON payment_transactions (payment_id);
