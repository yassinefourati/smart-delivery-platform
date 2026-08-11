CREATE TABLE products (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku         VARCHAR(64) NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    description VARCHAR(2000),
    price       NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    image_url   VARCHAR(500),
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    -- ON DELETE RESTRICT: a category with products cannot be deleted out from under
    -- them; the API surfaces this as 409 CATEGORY_IN_USE rather than a 500 (see
    -- CategoryService).
    category_id UUID NOT NULL REFERENCES categories (id) ON DELETE RESTRICT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category_id ON products (category_id);
CREATE INDEX idx_products_name ON products (name);
CREATE INDEX idx_products_active ON products (active);
