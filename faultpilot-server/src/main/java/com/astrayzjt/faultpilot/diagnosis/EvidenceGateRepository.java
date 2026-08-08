package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.EvidenceGateResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EvidenceGateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public EvidenceGateRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public UUID save(UUID proposalId, UUID critiqueId, EvidenceGateResult result) {
        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update("INSERT INTO evidence_gate_result (id,proposal_id,critique_id,status,result_json,created_at) VALUES (?,?,?,?,?::jsonb,?)",
                    id, proposalId, critiqueId, result.status().name(), objectMapper.writeValueAsString(result), Timestamp.from(Instant.now()));
            return id;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist evidence gate result", exception);
        }
    }

    public Optional<EvidenceGateResult> findLatestByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT gate.result_json FROM evidence_gate_result gate " +
                        "JOIN diagnosis_proposal proposal ON proposal.id=gate.proposal_id WHERE proposal.incident_id=? " +
                        "ORDER BY gate.created_at DESC LIMIT 1", rs -> rs.next() ? Optional.of(read(rs.getString(1))) : Optional.empty(), incidentId);
    }

    private EvidenceGateResult read(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, EvidenceGateResult.class);
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse evidence gate result", exception);
        }
    }
}
