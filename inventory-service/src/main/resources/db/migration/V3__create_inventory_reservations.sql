-- UNIQUE (order_id, product_id): a reservation request is idempotent per order+product
-- pair. A retried "reserve" call for the same order/product finds this row instead of
-- creating a duplicate and double-decrementing stock. See InventoryReservationService.
CREATE TABLE inventory_reservations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id UUID NOT NULL REFERENCES inventory (id) ON DELETE RESTRICT,
    order_id     UUID NOT NULL,
    product_id   UUID NOT NULL,
    quantity     INTEGER NOT NULL CHECK (quantity > 0),
    status       VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (order_id, product_id)
);

CREATE INDEX idx_reservations_order_id ON inventory_reservations (order_id);
