CREATE UNIQUE INDEX uq_evidence_active_run_tool_call
    ON evidence_record (run_id, tool_call_id)
    WHERE run_id IS NOT NULL
      AND tool_call_id IS NOT NULL
      AND evidence_status = 'ACTIVE';
