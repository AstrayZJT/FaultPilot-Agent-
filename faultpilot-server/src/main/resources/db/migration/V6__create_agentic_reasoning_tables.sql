CREATE TABLE IF NOT EXISTS agent_step_run (
    id UUID PRIMARY KEY,
    task_id UUID NOT NULL REFERENCES agent_task_run(id),
    step_index INTEGER NOT NULL,
    action VARCHAR(32) NOT NULL,
    tool_name VARCHAR(128),
    arguments_hash VARCHAR(128),
    decision_summary TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_id UUID REFERENCES evidence_record(id),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    UNIQUE(task_id, step_index)
);

CREATE INDEX IF NOT EXISTS idx_agent_step_task ON agent_step_run(task_id, step_index);

CREATE TABLE IF NOT EXISTS diagnosis_proposal (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES incident_run(id),
    investigation_round INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    proposal_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_diagnosis_proposal_incident ON diagnosis_proposal(incident_id, investigation_round, revision);

CREATE TABLE IF NOT EXISTS diagnosis_critique (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES diagnosis_proposal(id),
    verdict VARCHAR(32) NOT NULL,
    critique_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS evidence_gate_result (
    id UUID PRIMARY KEY,
    proposal_id UUID NOT NULL REFERENCES diagnosis_proposal(id),
    critique_id UUID REFERENCES diagnosis_critique(id),
    status VARCHAR(32) NOT NULL,
    result_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_task_evidence_link (
    task_id UUID NOT NULL REFERENCES agent_task_run(id),
    evidence_id UUID NOT NULL REFERENCES evidence_record(id),
    usage VARCHAR(32) NOT NULL,
    PRIMARY KEY(task_id, evidence_id, usage)
);
