CREATE TABLE IF NOT EXISTS lab_inventory (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(64) NOT NULL UNIQUE,
    available_quantity INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO lab_inventory (sku, available_quantity)
VALUES ('demo-item', 100)
ON CONFLICT (sku) DO NOTHING;

