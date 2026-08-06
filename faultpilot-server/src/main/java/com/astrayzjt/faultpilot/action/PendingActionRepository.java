package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;
import com.astrayzjt.faultpilot.common.domain.PendingAction;
import com.astrayzjt.faultpilot.common.domain.PendingActionStatus;
import com.astrayzjt.faultpilot.common.domain.RiskLevel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PendingActionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PendingActionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(PendingAction action) {
        try {
            jdbcTemplate.update("INSERT INTO pending_action " +
                            "(id,incident_id,action_code,risk_level,parameters_json,arguments_hash,status,idempotency_key,expires_at,version) " +
                            "VALUES (?,?,?,?,?::jsonb,?,?,?,?,0) ON CONFLICT (idempotency_key) DO NOTHING",
                    action.id(), action.incidentId(), action.actionCode().name(), action.riskLevel().name(),
                    objectMapper.writeValueAsString(action.parameters()), action.argumentsHash(), action.status().name(),
                    action.idempotencyKey(), Timestamp.from(action.expiresAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize pending action parameters", exception);
        }
    }

    public Optional<PendingAction> find(UUID actionId) {
        return query("SELECT * FROM pending_action WHERE id=?", actionId).stream().findFirst();
    }

    public Optional<PendingAction> findByIdempotencyKey(String key) {
        return query("SELECT * FROM pending_action WHERE idempotency_key=?", key).stream().findFirst();
    }

    public List<PendingAction> findByIncident(UUID incidentId) {
        return query("SELECT * FROM pending_action WHERE incident_id=? ORDER BY expires_at", incidentId);
    }

    public List<PendingAction> findByStatuses(List<PendingActionStatus> statuses) {
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        return query("SELECT * FROM pending_action WHERE status IN (" + placeholders + ") ORDER BY expires_at",
                statuses.stream().map(Enum::name).toArray());
    }

    public Optional<PendingAction> lock(UUID actionId) {
        return query("SELECT * FROM pending_action WHERE id=? FOR UPDATE", actionId).stream().findFirst();
    }

    public boolean updateStatus(UUID id, PendingActionStatus from, PendingActionStatus to, long version) {
        return jdbcTemplate.update("UPDATE pending_action SET status=?,version=version+1 WHERE id=? AND status=? AND version=?",
                to.name(), id, from.name(), version) == 1;
    }

    public void confirm(UUID id, String confirmedBy, Instant confirmedAt, long version) {
        jdbcTemplate.update("UPDATE pending_action SET status='CONFIRMED',confirmed_by=?,confirmed_at=?,version=version+1 " +
                        "WHERE id=? AND status='PENDING' AND version=?", confirmedBy, Timestamp.from(confirmedAt), id, version);
    }

    public void reject(UUID id, String confirmedBy, Instant at, long version) {
        jdbcTemplate.update("UPDATE pending_action SET status='REJECTED',confirmed_by=?,confirmed_at=?,version=version+1 " +
                        "WHERE id=? AND status='PENDING' AND version=?", confirmedBy, Timestamp.from(at), id, version);
    }

    public void markResult(UUID id, PendingActionStatus status, Map<String, Object> result,
                           String errorCode, String errorMessage) {
        try {
            jdbcTemplate.update("UPDATE pending_action SET status=?,executed_at=?,result_json=?::jsonb,error_code=?,error_message=?,version=version+1 WHERE id=?",
                    status.name(), Timestamp.from(Instant.now()), objectMapper.writeValueAsString(result == null ? Map.of() : result),
                    errorCode, errorMessage, id);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize action result", exception);
        }
    }

    public boolean markStarted(UUID id, long version) {
        return jdbcTemplate.update("UPDATE pending_action SET status='EXECUTING',started_at=?,version=version+1 WHERE id=? AND status='CONFIRMED' AND version=?",
                Timestamp.from(Instant.now()), id, version) == 1;
    }

    public void expirePending(Instant now) {
        jdbcTemplate.update("UPDATE pending_action SET status='EXPIRED',version=version+1 WHERE status='PENDING' AND expires_at<=?",
                Timestamp.from(now));
    }

    private List<PendingAction> query(String sql, Object... args) {
        return jdbcTemplate.query(sql, (rs, row) -> {
            try {
                return new PendingAction(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                        ActionCode.valueOf(rs.getString("action_code")), RiskLevel.valueOf(rs.getString("risk_level")),
                        objectMapper.readValue(rs.getString("parameters_json"), objectMapper.getTypeFactory()
                                .constructMapType(Map.class, String.class, Object.class)), rs.getString("arguments_hash"),
                        PendingActionStatus.valueOf(rs.getString("status")), rs.getString("idempotency_key"),
                        rs.getTimestamp("expires_at").toInstant(), rs.getString("confirmed_by"), instant(rs.getTimestamp("confirmed_at")),
                        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("executed_at")),
                        rs.getString("result_json") == null ? Map.of() : objectMapper.readValue(rs.getString("result_json"),
                                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)),
                        rs.getString("error_code"), rs.getString("error_message"), rs.getLong("version"));
            } catch (JsonProcessingException exception) {
                throw new java.sql.SQLException("Cannot parse pending action JSON", exception);
            }
        }, args);
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
