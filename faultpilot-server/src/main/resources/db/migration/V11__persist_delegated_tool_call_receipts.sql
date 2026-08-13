CREATE TABLE delegated_tool_call_receipt (
    run_id UUID NOT NULL REFERENCES incident_investigation_run (id),
    delegation_id UUID NOT NULL REFERENCES agent_delegation (id),
    tool_call_id VARCHAR(256) NOT NULL,
    evidence_id UUID NOT NULL REFERENCES evidence_record (id),
    agent_id VARCHAR(128) NOT NULL,
    tool_id VARCHAR(192) NOT NULL,
    capability_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, tool_call_id)
);

CREATE INDEX idx_delegated_tool_call_receipt_delegation
    ON delegated_tool_call_receipt (delegation_id, created_at);

CREATE INDEX idx_delegated_tool_call_receipt_evidence
    ON delegated_tool_call_receipt (evidence_id);
