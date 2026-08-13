ALTER TABLE jvm_agent_task
    ADD COLUMN selected_skill VARCHAR(128);

CREATE TABLE jvm_agent_tool_call (
    remote_task_id UUID NOT NULL REFERENCES jvm_agent_task (remote_task_id),
    step_index INTEGER NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (remote_task_id, step_index),
    UNIQUE (remote_task_id, tool_name),
    UNIQUE (tool_call_id)
);

CREATE INDEX idx_jvm_agent_tool_call_status
    ON jvm_agent_tool_call (remote_task_id, status, step_index);
