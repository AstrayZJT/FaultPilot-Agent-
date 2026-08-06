package com.astrayzjt.faultpilot.orchestration.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Repository
public class TraceRepository {

    private final JdbcTemplate jdbcTemplate;

    public TraceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void tool(UUID incidentId, UUID taskId, String agentType, String toolName,
                     String argumentsHash, String status, String resultSummary, String errorCode,
                     Instant startedAt, Instant completedAt) {
        jdbcTemplate.update("INSERT INTO tool_call_trace " +
                        "(incident_id,task_id,agent_type,tool_name,arguments_hash,status,result_summary,error_code,started_at,completed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)", incidentId, taskId, agentType, toolName, argumentsHash,
                status, resultSummary, errorCode, Timestamp.from(startedAt), completedAt == null ? null : Timestamp.from(completedAt));
    }

    public void model(UUID incidentId, UUID taskId, String modelName, String promptVersion,
                      Integer inputTokens, Integer outputTokens, Instant startedAt, String status) {
        jdbcTemplate.update("INSERT INTO model_call_trace " +
                        "(incident_id,task_id,model_name,prompt_version,input_tokens,output_tokens,latency_ms,status,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", incidentId, taskId, modelName, promptVersion, inputTokens,
                outputTokens, Duration.between(startedAt, Instant.now()).toMillis(), status, Timestamp.from(Instant.now()));
    }
}
