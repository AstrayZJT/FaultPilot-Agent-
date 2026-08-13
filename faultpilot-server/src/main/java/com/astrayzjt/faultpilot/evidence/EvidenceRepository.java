package com.astrayzjt.faultpilot.evidence;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
        jdbcTemplate.update("INSERT INTO evidence_record " +
                        "(id,incident_id,producer_task_id,evidence_type,source,entity,window_start,window_end,summary," +
                        "raw_data_reference,content_hash,collected_at,run_id,agent_id,tool_id,tool_call_id," +
                        "capability_version,evidence_status,structured_data_json) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)",
                evidence.evidenceId(), evidence.incidentId(), evidence.producerTaskId(), evidence.type().name(),
                evidence.source(), evidence.entity(), timestamp(evidence.windowStart()), timestamp(evidence.windowEnd()),
                evidence.summary(), evidence.rawDataReference(), evidence.contentHash(), timestamp(evidence.collectedAt()),
                evidence.runId(), evidence.agentId(), evidence.toolId(), evidence.toolCallId(), evidence.capabilityVersion(),
                evidence.status().name(), json(evidence.structuredData()));
        return evidence;
    }

    public List<Evidence> findByIncident(UUID incidentId) {
        return query(BASE_SELECT + " WHERE incident_id=? AND evidence_status='ACTIVE' ORDER BY collected_at", incidentId);
    }

    public List<Evidence> findActiveByRun(UUID runId) {
        return query(BASE_SELECT + " WHERE run_id=? AND evidence_status='ACTIVE' ORDER BY collected_at", runId);
    }

    public List<Evidence> findActiveByRunAndTask(UUID runId, UUID taskId) {
        return query(BASE_SELECT + " WHERE run_id=? AND producer_task_id=? AND evidence_status='ACTIVE' " +
                "ORDER BY collected_at", runId, taskId);
    }

    public void markRunStale(UUID runId) {
        jdbcTemplate.update("UPDATE evidence_record SET evidence_status='STALE' " +
                "WHERE run_id=? AND evidence_status='ACTIVE'", runId);
    }

    public void linkTaskEvidence(UUID taskId, UUID evidenceId, String usage) {
        jdbcTemplate.update("INSERT INTO agent_task_evidence_link(task_id,evidence_id,usage) VALUES (?,?,?) " +
                "ON CONFLICT (task_id,evidence_id,usage) DO NOTHING", taskId, evidenceId, usage);
    }

    private static final String BASE_SELECT = "SELECT id,incident_id,producer_task_id,run_id,agent_id,tool_id," +
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
