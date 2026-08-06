ALTER TABLE incident_run
    ADD COLUMN IF NOT EXISTS symptom TEXT,
    ADD COLUMN IF NOT EXISTS alert_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS start_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS end_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS endpoint_name VARCHAR(256),
    ADD COLUMN IF NOT EXISTS instance_name VARCHAR(256),
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(256),
    ADD COLUMN IF NOT EXISTS allow_remediation BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE agent_task_run (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    task_key VARCHAR(128) NOT NULL,
    agent_type VARCHAR(64) NOT NULL,
    objective TEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    max_steps INTEGER NOT NULL,
    investigation_round INTEGER NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_agent_task_incident ON agent_task_run (incident_id, started_at);

CREATE TABLE evidence_record (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    producer_task_id UUID REFERENCES agent_task_run (id),
    evidence_type VARCHAR(96) NOT NULL,
    source VARCHAR(256) NOT NULL,
    entity VARCHAR(256),
    window_start TIMESTAMPTZ,
    window_end TIMESTAMPTZ,
    summary TEXT NOT NULL,
    raw_data_reference VARCHAR(512),
    content_hash VARCHAR(128) NOT NULL,
    collected_at TIMESTAMPTZ NOT NULL,
    UNIQUE (incident_id, evidence_type, source, content_hash)
);

CREATE INDEX idx_evidence_incident ON evidence_record (incident_id, collected_at);

CREATE TABLE tool_call_trace (
    id BIGSERIAL PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    task_id UUID,
    agent_type VARCHAR(64),
    tool_name VARCHAR(128) NOT NULL,
    arguments_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_summary TEXT,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE model_call_trace (
    id BIGSERIAL PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    task_id UUID,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(128) NOT NULL,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms BIGINT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
