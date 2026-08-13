CREATE TABLE jvm_agent_task (
    remote_task_id UUID PRIMARY KEY,
    task_id UUID NOT NULL UNIQUE,
    idempotency_key VARCHAR(320) NOT NULL UNIQUE,
    request_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    steps_used INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_jvm_agent_task_status ON jvm_agent_task (status, updated_at);
