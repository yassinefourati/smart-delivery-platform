CREATE TABLE deliveries (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id  UUID NOT NULL UNIQUE REFERENCES shipments (id),
    agent_id     UUID NOT NULL REFERENCES delivery_agents (id),
    status       VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',
    assigned_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deliveries_agent_id ON deliveries (agent_id);
