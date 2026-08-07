package com.astrayzjt.faultpilot.evidence;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Evidence saveOrReuse(Evidence evidence) {
        Optional<Evidence> existing = jdbcTemplate.query("SELECT id,incident_id,producer_task_id,evidence_type,source," +
                        "entity,window_start,window_end,summary,raw_data_reference,content_hash,collected_at " +
                        "FROM evidence_record WHERE incident_id=? AND evidence_type=? AND source=? AND content_hash=?",
                (rs, row) -> map(rs), evidence.incidentId(), evidence.type().name(), evidence.source(), evidence.contentHash())
                .stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        jdbcTemplate.update("INSERT INTO evidence_record " +
                        "(id,incident_id,producer_task_id,evidence_type,source,entity,window_start,window_end,summary," +
                        "raw_data_reference,content_hash,collected_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                evidence.evidenceId(), evidence.incidentId(), evidence.producerTaskId(), evidence.type().name(),
                evidence.source(), evidence.entity(), timestamp(evidence.windowStart()), timestamp(evidence.windowEnd()),
                evidence.summary(), evidence.rawDataReference(), evidence.contentHash(), timestamp(evidence.collectedAt()));
        return evidence;
    }

    public List<Evidence> findByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT id,incident_id,producer_task_id,evidence_type,source,entity,window_start," +
                "window_end,summary,raw_data_reference,content_hash,collected_at FROM evidence_record " +
                "WHERE incident_id=? ORDER BY collected_at", (rs, row) -> map(rs), incidentId);
    }

    public void linkTaskEvidence(UUID taskId, UUID evidenceId, String usage) {
        jdbcTemplate.update("INSERT INTO agent_task_evidence_link(task_id,evidence_id,usage) VALUES (?,?,?) " +
                "ON CONFLICT (task_id,evidence_id,usage) DO NOTHING", taskId, evidenceId, usage);
    }

    private Evidence map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Evidence(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getObject("producer_task_id", UUID.class), EvidenceType.valueOf(rs.getString("evidence_type")),
                rs.getString("source"), rs.getString("entity"), instant(rs.getTimestamp("window_start")),
                instant(rs.getTimestamp("window_end")), rs.getString("summary"), rs.getString("raw_data_reference"),
                rs.getString("content_hash"), instant(rs.getTimestamp("collected_at")));
    }

    private Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
