CREATE TABLE runbook_document (
    id UUID PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    content TEXT NOT NULL,
    cause_code VARCHAR(96),
    reviewed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_runbook_reviewed_cause ON runbook_document (reviewed, cause_code);

INSERT INTO runbook_document (id,title,content,cause_code,reviewed,created_at,updated_at) VALUES
('00000000-0000-0000-0000-000000000001','CPU hotspot response','Confirm process CPU evidence, stop only the active lab CPU scenario, then verify CPU returns to normal.','JVM_CPU_HOTSPOT',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000002','Worker pool response','Confirm active and queued worker evidence before releasing the lab blocked-task scenario.','JVM_THREAD_POOL_EXHAUSTED',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000003','Slow SQL response','Use the database evidence and restore the indexed lab query only after operator confirmation.','DB_SLOW_QUERY',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000004','Connection pool response','Verify pool pressure is caused by the lab scenario, release held connections, and recheck pool health.','DB_POOL_EXHAUSTED',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('00000000-0000-0000-0000-000000000005','Dependency timeout response','Confirm downstream latency evidence and recover the inventory dependency timeout scenario.','DEPENDENCY_TIMEOUT',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE evaluation_run (
    id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    summary_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE evaluation_result (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES evaluation_run(id),
    case_code VARCHAR(96) NOT NULL,
    expected_cause VARCHAR(96) NOT NULL,
    actual_cause VARCHAR(96),
    expected_evidence_json JSONB NOT NULL,
    actual_evidence_json JSONB NOT NULL,
    correct BOOLEAN NOT NULL,
    evidence_recall NUMERIC(5,4) NOT NULL,
    tool_calls INTEGER NOT NULL DEFAULT 0,
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms BIGINT,
    recovery_verified BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_evaluation_result_run ON evaluation_result(run_id);
