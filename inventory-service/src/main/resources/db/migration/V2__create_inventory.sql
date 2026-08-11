-- product_id is an opaque reference to product-service's Product, not a foreign key --
-- inventory-service does not have (and must never take) direct database access to
-- product_db. See docs/database-design.md.
CREATE TABLE inventory (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id         UUID NOT NULL,
    warehouse_id       UUID NOT NULL REFERENCES warehouses (id) ON DELETE RESTRICT,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity  INTEGER NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    -- Optimistic lock: every UPDATE is issued as "... WHERE id = ? AND version = ?".
    -- Two concurrent reservation attempts against the same row race to increment this;
    -- the loser's UPDATE affects zero rows and Hibernate raises
    -- ObjectOptimisticLockingFailureException instead of silently overselling. See
    -- InventoryReservationService and docs/database-design.md.
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, warehouse_id)
);

CREATE INDEX idx_inventory_product_id ON inventory (product_id);
