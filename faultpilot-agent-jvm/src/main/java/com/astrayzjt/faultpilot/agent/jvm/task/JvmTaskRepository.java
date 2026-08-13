package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JvmTaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public JvmTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public CreateResult createOrGet(DelegationRequest request) {
        UUID remoteTaskId = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbcTemplate.update("INSERT INTO jvm_agent_task " +
                            "(remote_task_id,task_id,idempotency_key,request_json,status,evidence_ids_json,steps_used," +
                            "created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                    remoteTaskId, request.taskId(), request.idempotencyKey(), json(request),
                    TaskStatus.SUBMITTED.name(), "[]", 0, Timestamp.from(now), Timestamp.from(now));
            return new CreateResult(find(remoteTaskId).orElseThrow(), true);
        } catch (DuplicateKeyException duplicate) {
            JvmTaskRecord existing = findByIdempotencyKey(request.idempotencyKey())
                    .orElseThrow(() -> new IllegalArgumentException("A2A task ID was reused with another idempotency key"));
            if (!sameIdentity(existing.request(), request)) {
                throw new IllegalArgumentException("Idempotency key was reused with a different delegation request");
            }
            if (!existing.status().terminal()) {
                DelegationRequest refreshed = refresh(existing.request(), request);
                jdbcTemplate.update("UPDATE jvm_agent_task SET request_json=?,updated_at=? WHERE remote_task_id=? " +
                                "AND status IN ('SUBMITTED','RUNNING')", json(refreshed), Timestamp.from(Instant.now()),
                        existing.remoteTaskId());
                existing = find(existing.remoteTaskId()).orElseThrow();
            }
            return new CreateResult(existing, false);
        }
    }

    public Optional<JvmTaskRecord> find(UUID remoteTaskId) {
        return query("SELECT * FROM jvm_agent_task WHERE remote_task_id=?", remoteTaskId).stream().findFirst();
    }

    public Optional<JvmTaskRecord> findByIdempotencyKey(String idempotencyKey) {
        return query("SELECT * FROM jvm_agent_task WHERE idempotency_key=?", idempotencyKey).stream().findFirst();
    }

    public List<JvmTaskRecord> findActive() {
        return query("SELECT * FROM jvm_agent_task WHERE status IN ('SUBMITTED','RUNNING') ORDER BY created_at");
    }

    public boolean markRunning(UUID remoteTaskId) {
        return jdbcTemplate.update("UPDATE jvm_agent_task SET status='RUNNING',updated_at=? " +
                        "WHERE remote_task_id=? AND status IN ('SUBMITTED','RUNNING')",
                Timestamp.from(Instant.now()), remoteTaskId) == 1;
    }

    public JvmTaskProgress progress(UUID remoteTaskId) {
        JvmTaskRecord task = find(remoteTaskId).orElseThrow();
        return new JvmTaskProgress(task.selectedSkill(), findToolCalls(remoteTaskId), task.evidenceIds(),
                task.stepsUsed());
    }

    public boolean selectSkill(UUID remoteTaskId, String skillName) {
        if (skillName == null || !skillName.matches("[a-z][a-z0-9_-]{2,127}")) {
            throw new IllegalArgumentException("Invalid JVM Skill name");
        }
        JvmTaskRecord task = find(remoteTaskId).orElseThrow();
        if (task.status().terminal()) {
            return false;
        }
        if (!task.selectedSkill().isBlank()) {
            if (!task.selectedSkill().equals(skillName)) {
                throw new IllegalStateException("Persisted JVM task already selected another Skill");
            }
            return true;
        }
        return jdbcTemplate.update("UPDATE jvm_agent_task SET selected_skill=?,updated_at=? " +
                        "WHERE remote_task_id=? AND selected_skill IS NULL AND status IN ('SUBMITTED','RUNNING')",
                skillName, Timestamp.from(Instant.now()), remoteTaskId) == 1;
    }

    @Transactional
    public JvmToolCallRecord reserveTool(UUID remoteTaskId, String toolName) {
        if (toolName == null || !toolName.matches("[a-z][a-z0-9_-]{2,127}")) {
            throw new IllegalArgumentException("Invalid JVM Tool name");
        }
        Optional<JvmToolCallRecord> existing = findToolCall(remoteTaskId, toolName);
        if (existing.isPresent()) {
            return existing.get();
        }
        JvmTaskRecord task = find(remoteTaskId).orElseThrow();
        if (task.status().terminal()) {
            throw new IllegalStateException("Cannot reserve a Tool for a terminal JVM task");
        }
        if (task.stepsUsed() >= task.request().limits().maxSteps()) {
            throw new IllegalStateException("JVM task has exhausted its Tool step budget");
        }
        int stepIndex = task.stepsUsed();
        String toolCallId = task.taskId() + ":" + stepIndex + ":" + toolName;
        Instant now = Instant.now();
        jdbcTemplate.update("INSERT INTO jvm_agent_tool_call " +
                        "(remote_task_id,step_index,tool_name,tool_call_id,status,evidence_id,created_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?)", remoteTaskId, stepIndex, toolName, toolCallId,
                ToolCallStatus.RESERVED.name(), null, Timestamp.from(now), Timestamp.from(now));
        int updated = jdbcTemplate.update("UPDATE jvm_agent_task SET steps_used=steps_used+1,updated_at=? " +
                        "WHERE remote_task_id=? AND steps_used=? AND status IN ('SUBMITTED','RUNNING')",
                Timestamp.from(now), remoteTaskId, stepIndex);
        if (updated != 1) {
            throw new IllegalStateException("JVM task progress changed while reserving a Tool");
        }
        return findToolCall(remoteTaskId, toolName).orElseThrow();
    }

    @Transactional
    public boolean completeTool(UUID remoteTaskId, String toolCallId, UUID evidenceId) {
        JvmToolCallRecord call = findToolCallById(remoteTaskId, toolCallId).orElseThrow();
        if (call.status() == ToolCallStatus.COMPLETED) {
            if (!java.util.Objects.equals(call.evidenceId(), evidenceId)) {
                throw new IllegalStateException("Completed JVM Tool call cannot change its Evidence");
            }
            return true;
        }
        int updated = jdbcTemplate.update("UPDATE jvm_agent_tool_call SET status='COMPLETED',evidence_id=?,updated_at=? " +
                        "WHERE remote_task_id=? AND tool_call_id=? AND status='RESERVED'", evidenceId,
                Timestamp.from(Instant.now()), remoteTaskId, toolCallId);
        if (updated != 1) {
            return false;
        }
        if (evidenceId != null && !appendEvidence(remoteTaskId, evidenceId)) {
            throw new IllegalStateException("Cannot persist JVM Tool Evidence progress");
        }
        return true;
    }

    public boolean complete(UUID remoteTaskId, TaskOutcome outcome) {
        return jdbcTemplate.update("UPDATE jvm_agent_task SET status=?,evidence_ids_json=?,steps_used=?," +
                        "error_code=?,error_message=?,updated_at=? WHERE remote_task_id=? " +
                        "AND status IN ('SUBMITTED','RUNNING')",
                outcome.status().name(), json(outcome.evidenceIds()), outcome.stepsUsed(), outcome.errorCode(),
                safe(outcome.errorMessage()), Timestamp.from(Instant.now()), remoteTaskId) == 1;
    }

    public boolean appendEvidence(UUID remoteTaskId, UUID evidenceId) {
        JvmTaskRecord task = find(remoteTaskId).orElseThrow();
        if (task.status().terminal() || evidenceId == null) {
            return false;
        }
        List<UUID> updated = java.util.stream.Stream.concat(task.evidenceIds().stream(),
                        java.util.stream.Stream.of(evidenceId)).distinct().toList();
        return jdbcTemplate.update("UPDATE jvm_agent_task SET evidence_ids_json=?,updated_at=? " +
                        "WHERE remote_task_id=? AND status IN ('SUBMITTED','RUNNING')",
                json(updated), Timestamp.from(Instant.now()), remoteTaskId) == 1;
    }

    public boolean cancel(UUID remoteTaskId) {
        return jdbcTemplate.update("UPDATE jvm_agent_task SET status='CANCELED',error_code='A2A_TASK_CANCELED'," +
                        "error_message='Task canceled by the orchestrator',updated_at=? WHERE remote_task_id=? " +
                        "AND status IN ('SUBMITTED','RUNNING')", Timestamp.from(Instant.now()), remoteTaskId) == 1;
    }

    private List<JvmTaskRecord> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> {
            try {
                DelegationRequest request = objectMapper.readValue(rs.getString("request_json"), DelegationRequest.class);
                List<UUID> evidenceIds = objectMapper.readValue(rs.getString("evidence_ids_json"),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, UUID.class));
                return new JvmTaskRecord(rs.getObject("remote_task_id", UUID.class),
                        rs.getObject("task_id", UUID.class), rs.getString("idempotency_key"), request,
                        TaskStatus.valueOf(rs.getString("status")), rs.getString("selected_skill"), evidenceIds,
                        rs.getInt("steps_used"),
                        rs.getString("error_code"), rs.getString("error_message"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
            } catch (JsonProcessingException exception) {
                throw new SQLException("Cannot parse persisted JVM Agent task", exception);
            }
        }, arguments);
    }

    private List<JvmToolCallRecord> findToolCalls(UUID remoteTaskId) {
        return jdbcTemplate.query("SELECT * FROM jvm_agent_tool_call WHERE remote_task_id=? ORDER BY step_index",
                (rs, row) -> new JvmToolCallRecord(rs.getObject("remote_task_id", UUID.class),
                        rs.getInt("step_index"), rs.getString("tool_name"), rs.getString("tool_call_id"),
                        ToolCallStatus.valueOf(rs.getString("status")), rs.getObject("evidence_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                remoteTaskId);
    }

    private Optional<JvmToolCallRecord> findToolCall(UUID remoteTaskId, String toolName) {
        return findToolCalls(remoteTaskId).stream().filter(call -> call.toolName().equals(toolName)).findFirst();
    }

    private Optional<JvmToolCallRecord> findToolCallById(UUID remoteTaskId, String toolCallId) {
        return findToolCalls(remoteTaskId).stream().filter(call -> call.toolCallId().equals(toolCallId)).findFirst();
    }

    private boolean sameIdentity(DelegationRequest left, DelegationRequest right) {
        return left.schemaVersion().equals(right.schemaVersion()) && left.taskId().equals(right.taskId())
                && left.idempotencyKey().equals(right.idempotencyKey())
                && left.capabilityVersion().equals(right.capabilityVersion())
                && left.incident().equals(right.incident()) && left.objective().equals(right.objective())
                && left.limits().maxSteps() == right.limits().maxSteps();
    }

    private DelegationRequest refresh(DelegationRequest existing, DelegationRequest incoming) {
        Instant deadline = existing.limits().deadline().isAfter(incoming.limits().deadline())
                ? existing.limits().deadline() : incoming.limits().deadline();
        List<UUID> evidenceIds = java.util.stream.Stream.concat(existing.availableEvidenceIds().stream(),
                incoming.availableEvidenceIds().stream()).distinct().toList();
        return new DelegationRequest(existing.schemaVersion(), existing.taskId(), existing.idempotencyKey(),
                existing.capabilityVersion(), existing.incident(), existing.objective(), evidenceIds,
                new DelegationRequest.Limits(existing.limits().maxSteps(), deadline));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize JVM Agent task", exception);
        }
    }

    private String safe(String value) {
        return value == null ? null : value.substring(0, Math.min(500, value.length()));
    }

    public record CreateResult(JvmTaskRecord task, boolean created) {
    }
}
