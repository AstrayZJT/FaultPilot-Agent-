package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DiagnosisRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DiagnosisRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(UUID incidentId, DiagnosisDecision decision) {
        try {
            jdbcTemplate.update("INSERT INTO diagnosis_report " +
                            "(incident_id,status,primary_cause,contributing_factors_json,supporting_evidence_ids_json," +
                            "counter_evidence_ids_json,missing_evidence_types_json,summary,created_at) " +
                            "VALUES (?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?) " +
                            "ON CONFLICT (incident_id) DO UPDATE SET status=EXCLUDED.status,primary_cause=EXCLUDED.primary_cause," +
                            "contributing_factors_json=EXCLUDED.contributing_factors_json,supporting_evidence_ids_json=EXCLUDED.supporting_evidence_ids_json," +
                            "counter_evidence_ids_json=EXCLUDED.counter_evidence_ids_json,missing_evidence_types_json=EXCLUDED.missing_evidence_types_json," +
                            "summary=EXCLUDED.summary,created_at=EXCLUDED.created_at",
                    incidentId, decision.status().name(), decision.primaryCause().name(), json(decision.contributingFactors()),
                    json(decision.supportingEvidenceIds()), json(decision.counterEvidenceIds()), json(decision.missingEvidenceTypes()),
                    decision.summary(), Timestamp.from(Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist diagnosis", exception);
        }
    }

    public Optional<DiagnosisDecision> find(UUID incidentId) {
        return jdbcTemplate.query("SELECT status,primary_cause,contributing_factors_json,supporting_evidence_ids_json," +
                        "counter_evidence_ids_json,missing_evidence_types_json,summary FROM diagnosis_report WHERE incident_id=?",
                rs -> rs.next() ? Optional.of(read(rs)) : Optional.empty(), incidentId);
    }

    private DiagnosisDecision read(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new DiagnosisDecision(
                    com.astrayzjt.faultpilot.common.domain.DiagnosisStatus.valueOf(rs.getString("status")),
                    com.astrayzjt.faultpilot.common.domain.CauseCode.valueOf(rs.getString("primary_cause")),
                    objectMapper.readValue(rs.getString("contributing_factors_json"), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, com.astrayzjt.faultpilot.common.domain.CauseCode.class)),
                    objectMapper.readValue(rs.getString("supporting_evidence_ids_json"), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, UUID.class)),
                    objectMapper.readValue(rs.getString("counter_evidence_ids_json"), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, UUID.class)),
                    objectMapper.readValue(rs.getString("missing_evidence_types_json"), objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, com.astrayzjt.faultpilot.common.domain.EvidenceType.class)),
                    rs.getString("summary"));
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse diagnosis JSON", exception);
        }
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}

