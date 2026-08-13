package com.astrayzjt.faultpilot.agent.distributed.persistence;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentDelegationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentDelegationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean insert(AgentDelegation delegation) {
        return jdbcTemplate.update("INSERT INTO agent_delegation " +
                        "(id,run_id,incident_id,round,agent_id,agent_type,capability_version,objective,objective_hash," +
                        "idempotency_key,remote_task_id,status,artifact_json,started_at,completed_at,error_code,error_message,version) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING",
                delegation.delegationId(), delegation.runId(), delegation.incidentId(), delegation.round(),
                delegation.agentId(), delegation.agentType().name(), delegation.capabilityVersion(),
                delegation.objective(), delegation.objectiveHash(), delegation.idempotencyKey(),
                delegation.remoteTaskId(), delegation.status().name(), json(delegation.artifact()),
                timestamp(delegation.startedAt()), timestamp(delegation.completedAt()), delegation.errorCode(),
                delegation.errorMessage(), delegation.version()) == 1;
    }

    public Optional<AgentDelegation> find(UUID delegationId) {
        return query("SELECT * FROM agent_delegation WHERE id=?", delegationId).stream().findFirst();
    }

    public Optional<AgentDelegation> findByIdempotencyKey(String key) {
        return query("SELECT * FROM agent_delegation WHERE idempotency_key=?", key).stream().findFirst();
    }

    public List<AgentDelegation> findByRun(UUID runId) {
        return query("SELECT * FROM agent_delegation WHERE run_id=? ORDER BY round,id", runId);
    }

    public List<AgentDelegation> findRecoverableByRun(UUID runId) {
        return query("SELECT * FROM agent_delegation WHERE run_id=? AND status IN ('PENDING','SUBMITTED','RUNNING') " +
                "ORDER BY round,id", runId);
    }

    public void markRecoverableStale(UUID runId) {
        jdbcTemplate.update("UPDATE agent_delegation SET status='STALE',completed_at=CURRENT_TIMESTAMP," +
                "error_code='CAPABILITY_CHANGED',error_message='Agent capability changed during recovery'," +
                "version=version+1 WHERE run_id=? AND status IN ('PENDING','SUBMITTED','RUNNING')", runId);
    }

    public boolean transition(UUID delegationId, DelegationStatus expected, DelegationStatus target, long version,
                              String remoteTaskId, EvidenceReferenceArtifact artifact, String errorCode,
                              String errorMessage, Instant completedAt) {
        if (!expected.canTransitionTo(target)) {
            throw new IllegalArgumentException("Invalid delegation transition: " + expected + " -> " + target);
        }
        if (target == DelegationStatus.COMPLETED && artifact == null) {
            throw new IllegalArgumentException("Completed delegation requires an Evidence artifact");
        }
        if (target.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("Terminal delegation transition must provide completedAt only for terminal state");
        }
        if (artifact != null && (!delegationId.equals(artifact.taskId())
                || target != artifact.executionStatus())) {
            throw new IllegalArgumentException("Evidence artifact does not belong to the target delegation state");
        }
        return jdbcTemplate.update("UPDATE agent_delegation SET status=?,remote_task_id=COALESCE(?,remote_task_id)," +
                        "artifact_json=?::jsonb,error_code=?,error_message=?,started_at=COALESCE(started_at,CURRENT_TIMESTAMP)," +
                        "completed_at=?,version=version+1 WHERE id=? AND status=? AND version=?", target.name(),
                remoteTaskId, json(artifact), errorCode, errorMessage, timestamp(completedAt), delegationId,
                expected.name(), version) == 1;
    }

    private List<AgentDelegation> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> {
            try {
                String artifactJson = rs.getString("artifact_json");
                EvidenceReferenceArtifact artifact = artifactJson == null ? null
                        : objectMapper.readValue(artifactJson, EvidenceReferenceArtifact.class);
                return new AgentDelegation(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                        rs.getObject("incident_id", UUID.class), rs.getInt("round"), rs.getString("agent_id"),
                        AgentType.valueOf(rs.getString("agent_type")), rs.getString("capability_version"),
                        rs.getString("objective"), rs.getString("objective_hash"), rs.getString("idempotency_key"),
                        rs.getString("remote_task_id"), DelegationStatus.valueOf(rs.getString("status")), artifact,
                        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")),
                        rs.getString("error_code"), rs.getString("error_message"), rs.getLong("version"));
            } catch (JsonProcessingException exception) {
                throw new SQLException("Cannot parse Agent delegation artifact", exception);
            }
        }, arguments);
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Agent delegation artifact", exception);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
