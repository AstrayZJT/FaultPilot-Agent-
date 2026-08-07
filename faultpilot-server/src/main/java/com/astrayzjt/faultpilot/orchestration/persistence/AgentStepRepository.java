package com.astrayzjt.faultpilot.orchestration.persistence;

import com.astrayzjt.faultpilot.common.domain.AgentStepDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Repository
public class AgentStepRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentStepRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID recordDecision(UUID taskId, int stepIndex, AgentStepDecision decision, String status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO agent_step_run " +
                        "(id,task_id,step_index,action,tool_name,arguments_hash,decision_summary,status,started_at,completed_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, taskId, stepIndex, decision.action().name(), blankToNull(decision.toolName()),
                argumentsHash(decision), decision.decisionSummary(), status, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
        return id;
    }

    public void attachEvidence(UUID stepId, UUID evidenceId, String status) {
        jdbcTemplate.update("UPDATE agent_step_run SET evidence_id=?, status=?, completed_at=? WHERE id=?",
                evidenceId, status, Timestamp.from(Instant.now()), stepId);
    }

    private String argumentsHash(AgentStepDecision decision) {
        try {
            return sha256(objectMapper.writeValueAsString(decision.arguments()));
        } catch (JsonProcessingException exception) {
            return sha256("{}");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
