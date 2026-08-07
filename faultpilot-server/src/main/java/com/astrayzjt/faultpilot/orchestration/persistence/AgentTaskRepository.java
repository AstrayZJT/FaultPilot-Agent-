package com.astrayzjt.faultpilot.orchestration.persistence;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentTaskStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.incident.api.InvestigationDetail;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AgentTaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(AgentTask task) {
        jdbcTemplate.update("INSERT INTO agent_task_run " +
                        "(id,incident_id,task_key,agent_type,objective,status,max_steps,investigation_round) " +
                        "VALUES (?,?,?,?,?,?,?,?)", task.taskId(), task.incidentId(), task.taskKey(),
                task.agentType().name(), task.objective(), task.status().name(), task.maxSteps(), task.investigationRound());
    }

    public void markRunning(AgentTask task) {
        jdbcTemplate.update("UPDATE agent_task_run SET status='RUNNING', started_at=? WHERE id=?",
                Timestamp.from(Instant.now()), task.taskId());
    }

    public void complete(AgentTask task, AgentTaskStatus status, AgentFinding finding, String errorMessage) {
        try {
            jdbcTemplate.update("UPDATE agent_task_run SET status=?, completed_at=?, finding_json=?::jsonb, error_message=? WHERE id=?",
                    status.name(), Timestamp.from(Instant.now()), finding == null ? null : objectMapper.writeValueAsString(finding),
                    errorMessage, task.taskId());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist agent finding", exception);
        }
    }

    public void interruptRunning(UUID incidentId) {
        jdbcTemplate.update("UPDATE agent_task_run SET status='INTERRUPTED', completed_at=CURRENT_TIMESTAMP, " +
                "error_message='Interrupted by service restart' WHERE incident_id=? AND status='RUNNING'", incidentId);
    }

    public List<AgentFinding> findFindingsByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT finding_json FROM agent_task_run WHERE incident_id=? AND finding_json IS NOT NULL " +
                        "ORDER BY investigation_round, completed_at", (rs, row) -> readFinding(rs.getString("finding_json")), incidentId);
    }

    public List<InvestigationDetail.AgentTaskSummary> findTaskSummariesByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT id,agent_type,objective,status,investigation_round,max_steps,started_at,completed_at," +
                        "finding_json,error_message FROM agent_task_run WHERE incident_id=? " +
                        "ORDER BY investigation_round, started_at NULLS LAST, id",
                (rs, row) -> new InvestigationDetail.AgentTaskSummary(
                        rs.getObject("id", UUID.class),
                        AgentType.valueOf(rs.getString("agent_type")),
                        rs.getString("objective"),
                        rs.getString("status"),
                        rs.getInt("investigation_round"),
                        rs.getInt("max_steps"),
                        instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at")),
                        rs.getString("finding_json") == null ? null : readFinding(rs.getString("finding_json")),
                        safeErrorCode(rs.getString("error_message"))),
                incidentId);
    }

    private AgentFinding readFinding(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, AgentFinding.class);
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse agent finding", exception);
        }
    }

    private java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private String safeErrorCode(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        int delimiter = errorMessage.indexOf(':');
        String value = delimiter > 0 ? errorMessage.substring(0, delimiter) : errorMessage;
        return value.substring(0, Math.min(80, value.length()));
    }
}
