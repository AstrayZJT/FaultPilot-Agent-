CREATE TABLE IF NOT EXISTS lab_scenario_run (
    id UUID PRIMARY KEY,
    scenario_code VARCHAR(64) NOT NULL,
    target_service VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    injected_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recovered_at TIMESTAMPTZ,
    started_by VARCHAR(128),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lab_active_scenario_code
    ON lab_scenario_run (scenario_code)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS lab_orders (
    id BIGSERIAL PRIMARY KEY,
    order_number VARCHAR(64) NOT NULL UNIQUE,
    item_name VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO lab_orders (order_number, item_name, quantity)
VALUES ('order-demo-001', 'demo-item', 1)
ON CONFLICT (order_number) DO NOTHING;

