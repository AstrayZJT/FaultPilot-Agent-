CREATE TABLE incident_run (
    id UUID PRIMARY KEY,
    status VARCHAR(64) NOT NULL,
    service_name VARCHAR(128) NOT NULL,
    raw_request_json JSONB,
    normalized_snapshot_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_incident_run_status_updated_at
    ON incident_run (status, updated_at);

CREATE TABLE incident_event (
    id BIGSERIAL PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    event_type VARCHAR(64) NOT NULL,
    payload_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_incident_event_incident_id_id
    ON incident_event (incident_id, id);

