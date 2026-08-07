package com.astrayzjt.faultpilot.orchestration.persistence;

import com.astrayzjt.faultpilot.common.domain.AgentStepDecision;
import com.astrayzjt.faultpilot.common.domain.AgentStepAction;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.incident.api.InvestigationDetail;
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

    public java.util.List<InvestigationDetail.AgentStepSummary> findStepSummariesByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT step.id AS step_id,step.task_id,task.agent_type,task.investigation_round," +
                        "step.step_index,step.action,step.tool_name,step.decision_summary,step.status,step.evidence_id," +
                        "step.started_at,step.completed_at FROM agent_step_run step " +
                        "JOIN agent_task_run task ON task.id=step.task_id WHERE task.incident_id=? " +
                        "ORDER BY task.investigation_round,task.started_at NULLS LAST,step.step_index",
                (rs, row) -> new InvestigationDetail.AgentStepSummary(
                        rs.getObject("step_id", UUID.class),
                        rs.getObject("task_id", UUID.class),
                        AgentType.valueOf(rs.getString("agent_type")),
                        rs.getInt("investigation_round"),
                        rs.getInt("step_index"),
                        AgentStepAction.valueOf(rs.getString("action")),
                        rs.getString("tool_name"),
                        rs.getString("decision_summary"),
                        rs.getString("status"),
                        rs.getObject("evidence_id", UUID.class),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at"))),
                incidentId);
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

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
