ALTER TABLE evidence_record
    ADD COLUMN delegation_id UUID REFERENCES agent_delegation (id);

CREATE INDEX idx_evidence_delegation
    ON evidence_record (delegation_id, collected_at)
    WHERE delegation_id IS NOT NULL;

CREATE TABLE agent_delegation_evidence_link (
    delegation_id UUID NOT NULL REFERENCES agent_delegation (id),
    evidence_id UUID NOT NULL REFERENCES evidence_record (id),
    PRIMARY KEY (delegation_id, evidence_id)
);
