package com.astrayzjt.faultpilot.orchestration.persistence;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentTaskStatus;
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

    private AgentFinding readFinding(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, AgentFinding.class);
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse agent finding", exception);
        }
    }
}
