CREATE TABLE capability_snapshot (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    agents_json JSONB NOT NULL,
    discovered_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_capability_snapshot_discovered_at
    ON capability_snapshot (discovered_at DESC);

CREATE TABLE incident_investigation_run (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    capability_snapshot_id UUID NOT NULL REFERENCES capability_snapshot (id),
    status VARCHAR(32) NOT NULL,
    baseline_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    restart_reason VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_investigation_run_incident_started
    ON incident_investigation_run (incident_id, started_at DESC);

CREATE INDEX idx_investigation_run_status
    ON incident_investigation_run (status, started_at);

CREATE UNIQUE INDEX uq_investigation_run_active_incident
    ON incident_investigation_run (incident_id)
    WHERE status IN ('PENDING', 'RUNNING');

CREATE TABLE agent_delegation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES incident_investigation_run (id),
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    round INTEGER NOT NULL CHECK (round > 0),
    agent_id VARCHAR(128) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    capability_version VARCHAR(128) NOT NULL,
    objective TEXT NOT NULL,
    objective_hash VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(320) NOT NULL UNIQUE,
    remote_task_id VARCHAR(256),
    status VARCHAR(32) NOT NULL,
    artifact_json JSONB,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_code VARCHAR(64),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_delegation_run_round
    ON agent_delegation (run_id, round, id);

CREATE INDEX idx_agent_delegation_status
    ON agent_delegation (status, started_at);

ALTER TABLE evidence_record
    ADD COLUMN run_id UUID REFERENCES incident_investigation_run (id),
    ADD COLUMN agent_id VARCHAR(128),
    ADD COLUMN tool_id VARCHAR(192),
    ADD COLUMN tool_call_id VARCHAR(256),
    ADD COLUMN capability_version VARCHAR(128),
    ADD COLUMN evidence_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN structured_data_json JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX idx_evidence_run_status
    ON evidence_record (run_id, evidence_status, collected_at);
