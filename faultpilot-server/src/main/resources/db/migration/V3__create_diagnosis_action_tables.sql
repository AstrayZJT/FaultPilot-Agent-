ALTER TABLE agent_task_run ADD COLUMN IF NOT EXISTS finding_json JSONB;

CREATE TABLE diagnosis_report (
    incident_id UUID PRIMARY KEY REFERENCES incident_run (id),
    status VARCHAR(64) NOT NULL,
    primary_cause VARCHAR(96) NOT NULL,
    contributing_factors_json JSONB NOT NULL,
    supporting_evidence_ids_json JSONB NOT NULL,
    counter_evidence_ids_json JSONB NOT NULL,
    missing_evidence_types_json JSONB NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE pending_action (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run (id),
    action_code VARCHAR(96) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    parameters_json JSONB NOT NULL,
    arguments_hash VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    confirmed_by VARCHAR(128),
    confirmed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    result_json JSONB,
    error_code VARCHAR(64),
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0
);

