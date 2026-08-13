package com.astrayzjt.faultpilot.evidence;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvidenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Evidence saveOrReuse(Evidence evidence) {
        return saveOrReuse(evidence, null);
    }

    @Transactional
    public Evidence saveOrReuseDelegated(Evidence evidence, UUID delegationId) {
        if (delegationId == null || !delegationId.equals(evidence.producerTaskId())) {
            throw new IllegalArgumentException("Delegated Evidence must reference its delegation task");
        }
        if (evidence.runId() == null || evidence.toolCallId() == null || evidence.toolCallId().isBlank()) {
            throw new IllegalArgumentException("Delegated Evidence must contain a Run and Tool call ID");
        }

        Optional<DelegatedToolCallReceipt> receipt = findReceipt(evidence.runId(), evidence.toolCallId());
        if (receipt.isPresent()) {
            requireSameReceipt(receipt.get(), evidence, delegationId);
            Evidence saved = findActiveById(receipt.get().evidenceId())
                    .orElseThrow(() -> new IllegalStateException("Delegated Tool call receipt points to missing Evidence"));
            requireReceiptEvidence(saved, evidence, delegationId);
            linkDelegationEvidence(delegationId, saved.evidenceId());
            return saved;
        }

        Evidence saved = saveOrReuse(evidence, delegationId);
        int receiptInserted = jdbcTemplate.update("INSERT INTO delegated_tool_call_receipt " +
                        "(run_id,delegation_id,tool_call_id,evidence_id,agent_id,tool_id,capability_version,created_at) " +
                        "VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP) " +
                        "ON CONFLICT (run_id,tool_call_id) DO NOTHING",
                evidence.runId(), delegationId, evidence.toolCallId(), saved.evidenceId(), evidence.agentId(),
                evidence.toolId(), evidence.capabilityVersion());
        if (receiptInserted == 0) {
            DelegatedToolCallReceipt raced = findReceipt(evidence.runId(), evidence.toolCallId())
                    .orElseThrow(() -> new IllegalStateException("Concurrent Tool call receipt was not persisted"));
            requireSameReceipt(raced, evidence, delegationId);
            saved = findActiveById(raced.evidenceId())
                    .orElseThrow(() -> new IllegalStateException("Delegated Tool call receipt points to missing Evidence"));
            requireReceiptEvidence(saved, evidence, delegationId);
        }
        linkDelegationEvidence(delegationId, saved.evidenceId());
        return saved;
    }

    private void linkDelegationEvidence(UUID delegationId, UUID evidenceId) {
        jdbcTemplate.update("INSERT INTO agent_delegation_evidence_link(delegation_id,evidence_id) VALUES (?,?) " +
                "ON CONFLICT (delegation_id,evidence_id) DO NOTHING", delegationId, evidenceId);
    }

    private Evidence saveOrReuse(Evidence evidence, UUID delegationId) {
        Optional<Evidence> sameCall = findByToolCall(evidence);
        if (sameCall.isPresent()) {
            requireSameToolCall(sameCall.get(), evidence);
            return sameCall.get();
        }
        Optional<Evidence> existing = evidence.runId() == null
                ? query(BASE_SELECT + " WHERE incident_id=? AND run_id IS NULL AND evidence_status='ACTIVE' " +
                                "AND evidence_type=? AND source=? AND content_hash=?",
                        evidence.incidentId(), evidence.type().name(), evidence.source(), evidence.contentHash())
                        .stream().findFirst()
                : query(BASE_SELECT + " WHERE run_id=? AND evidence_status='ACTIVE' AND evidence_type=? " +
                                "AND source=? AND content_hash=?", evidence.runId(), evidence.type().name(),
                        evidence.source(), evidence.contentHash()).stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        int inserted = jdbcTemplate.update("INSERT INTO evidence_record " +
                        "(id,incident_id,producer_task_id,evidence_type,source,entity,window_start,window_end,summary," +
                        "raw_data_reference,content_hash,collected_at,run_id,agent_id,tool_id,tool_call_id," +
                        "capability_version,evidence_status,structured_data_json,delegation_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) ON CONFLICT DO NOTHING",
                evidence.evidenceId(), evidence.incidentId(), delegationId == null ? evidence.producerTaskId() : null,
                evidence.type().name(), evidence.source(), evidence.entity(), timestamp(evidence.windowStart()),
                timestamp(evidence.windowEnd()), evidence.summary(), evidence.rawDataReference(),
                evidence.contentHash(), timestamp(evidence.collectedAt()), evidence.runId(), evidence.agentId(),
                evidence.toolId(), evidence.toolCallId(), evidence.capabilityVersion(), evidence.status().name(),
                json(evidence.structuredData()), delegationId);
        if (inserted == 1) {
            return evidence;
        }
        Optional<Evidence> racedCall = findByToolCall(evidence);
        if (racedCall.isPresent()) {
            requireSameToolCall(racedCall.get(), evidence);
            return racedCall.get();
        }
        Optional<Evidence> racedContent = evidence.runId() == null
                ? query(BASE_SELECT + " WHERE incident_id=? AND run_id IS NULL AND evidence_status='ACTIVE' " +
                                "AND evidence_type=? AND source=? AND content_hash=?", evidence.incidentId(),
                        evidence.type().name(), evidence.source(), evidence.contentHash()).stream().findFirst()
                : query(BASE_SELECT + " WHERE run_id=? AND evidence_status='ACTIVE' AND evidence_type=? " +
                                "AND source=? AND content_hash=?", evidence.runId(), evidence.type().name(),
                        evidence.source(), evidence.contentHash()).stream().findFirst();
        return racedContent.orElseThrow(() -> new IllegalStateException(
                "Evidence insert was ignored but no matching active Evidence was found"));
    }

    private Optional<Evidence> findByToolCall(Evidence evidence) {
        if (evidence.runId() == null || evidence.toolCallId() == null || evidence.toolCallId().isBlank()) {
            return Optional.empty();
        }
        return query(BASE_SELECT + " WHERE run_id=? AND tool_call_id=? AND evidence_status='ACTIVE'",
                evidence.runId(), evidence.toolCallId()).stream().findFirst();
    }

    private Optional<Evidence> findActiveById(UUID evidenceId) {
        return query(BASE_SELECT + " WHERE id=? AND evidence_status='ACTIVE'", evidenceId).stream().findFirst();
    }

    private Optional<DelegatedToolCallReceipt> findReceipt(UUID runId, String toolCallId) {
        return jdbcTemplate.query("SELECT run_id,delegation_id,tool_call_id,evidence_id,agent_id,tool_id," +
                        "capability_version FROM delegated_tool_call_receipt WHERE run_id=? AND tool_call_id=?",
                (rs, row) -> new DelegatedToolCallReceipt(rs.getObject("run_id", UUID.class),
                        rs.getObject("delegation_id", UUID.class), rs.getString("tool_call_id"),
                        rs.getObject("evidence_id", UUID.class), rs.getString("agent_id"),
                        rs.getString("tool_id"), rs.getString("capability_version")), runId, toolCallId)
                .stream().findFirst();
    }

    private void requireSameReceipt(DelegatedToolCallReceipt receipt, Evidence incoming, UUID delegationId) {
        if (!receipt.runId().equals(incoming.runId()) || !receipt.delegationId().equals(delegationId)
                || !receipt.toolCallId().equals(incoming.toolCallId())
                || !java.util.Objects.equals(receipt.agentId(), incoming.agentId())
                || !java.util.Objects.equals(receipt.toolId(), incoming.toolId())
                || !java.util.Objects.equals(receipt.capabilityVersion(), incoming.capabilityVersion())) {
            throw new IllegalArgumentException("Delegated Tool call receipt identity mismatch");
        }
    }

    private void requireReceiptEvidence(Evidence saved, Evidence incoming, UUID delegationId) {
        if (!saved.incidentId().equals(incoming.incidentId()) || !saved.runId().equals(incoming.runId())
                || saved.status() != EvidenceStatus.ACTIVE || saved.type() != incoming.type()
                || !java.util.Objects.equals(saved.source(), incoming.source())) {
            throw new IllegalArgumentException("Delegated Tool call receipt points to incompatible Evidence");
        }
    }

    private record DelegatedToolCallReceipt(UUID runId, UUID delegationId, String toolCallId, UUID evidenceId,
                                            String agentId, String toolId, String capabilityVersion) {
    }

    private void requireSameToolCall(Evidence existing, Evidence incoming) {
        if (!existing.incidentId().equals(incoming.incidentId()) || existing.type() != incoming.type()
                || !java.util.Objects.equals(existing.producerTaskId(), incoming.producerTaskId())
                || !java.util.Objects.equals(existing.agentId(), incoming.agentId())
                || !java.util.Objects.equals(existing.toolId(), incoming.toolId())
                || !java.util.Objects.equals(existing.capabilityVersion(), incoming.capabilityVersion())) {
            throw new IllegalArgumentException("Tool call ID was reused for different Evidence");
        }
    }

    public List<Evidence> findByIncident(UUID incidentId) {
        return query(BASE_SELECT + " WHERE incident_id=? AND evidence_status='ACTIVE' ORDER BY collected_at", incidentId);
    }

    public List<Evidence> findActiveByRun(UUID runId) {
        return query(BASE_SELECT + " WHERE run_id=? AND evidence_status='ACTIVE' ORDER BY collected_at", runId);
    }

    public List<Evidence> findActiveByRunAndTask(UUID runId, UUID taskId) {
        return query(BASE_SELECT + " WHERE run_id=? AND (producer_task_id=? OR delegation_id=? OR EXISTS " +
                "(SELECT 1 FROM agent_delegation_evidence_link link " +
                "WHERE link.evidence_id=evidence_record.id AND link.delegation_id=?)) " +
                "AND evidence_status='ACTIVE' ORDER BY collected_at", runId, taskId, taskId, taskId);
    }

    public void markRunStale(UUID runId) {
        jdbcTemplate.update("UPDATE evidence_record SET evidence_status='STALE' " +
                "WHERE run_id=? AND evidence_status='ACTIVE'", runId);
    }

    public void linkTaskEvidence(UUID taskId, UUID evidenceId, String usage) {
        jdbcTemplate.update("INSERT INTO agent_task_evidence_link(task_id,evidence_id,usage) VALUES (?,?,?) " +
                "ON CONFLICT (task_id,evidence_id,usage) DO NOTHING", taskId, evidenceId, usage);
    }

    private static final String BASE_SELECT = "SELECT id,incident_id,COALESCE(producer_task_id,delegation_id) " +
            "AS producer_task_id,run_id,agent_id,tool_id," +
            "tool_call_id,capability_version,evidence_status,evidence_type,source,entity,window_start,window_end," +
            "summary,raw_data_reference,content_hash,structured_data_json,collected_at FROM evidence_record";

    private List<Evidence> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> map(rs), arguments);
    }

    private Evidence map(java.sql.ResultSet rs) throws SQLException {
        try {
            String structured = rs.getString("structured_data_json");
            Map<String, Object> structuredData = structured == null ? Map.of()
                    : objectMapper.readValue(structured, objectMapper.getTypeFactory()
                    .constructMapType(Map.class, String.class, Object.class));
            return new Evidence(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                    rs.getObject("producer_task_id", UUID.class), rs.getObject("run_id", UUID.class),
                    rs.getString("agent_id"), rs.getString("tool_id"), rs.getString("tool_call_id"),
                    rs.getString("capability_version"), EvidenceStatus.valueOf(rs.getString("evidence_status")),
                    EvidenceType.valueOf(rs.getString("evidence_type")), rs.getString("source"),
                    rs.getString("entity"), instant(rs.getTimestamp("window_start")),
                    instant(rs.getTimestamp("window_end")), rs.getString("summary"),
                    rs.getString("raw_data_reference"), rs.getString("content_hash"), structuredData,
                    instant(rs.getTimestamp("collected_at")));
        } catch (JsonProcessingException exception) {
            throw new SQLException("Cannot parse Evidence structured data", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Evidence structured data", exception);
        }
    }

    private Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
