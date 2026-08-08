package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DiagnosisProposalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DiagnosisProposalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(DiagnosisProposal proposal) {
        try {
            jdbcTemplate.update("INSERT INTO diagnosis_proposal " +
                            "(id,incident_id,investigation_round,revision,status,proposal_json,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
                    proposal.proposalId(), proposal.incidentId(), proposal.investigationRound(), proposal.revision(),
                    proposal.status().name(), objectMapper.writeValueAsString(proposal), Timestamp.from(Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist diagnosis proposal", exception);
        }
    }

    public List<DiagnosisProposal> findByIncident(UUID incidentId) {
        return jdbcTemplate.query("SELECT proposal_json FROM diagnosis_proposal WHERE incident_id=? " +
                        "ORDER BY investigation_round, revision, created_at", (rs, row) -> read(rs.getString("proposal_json")), incidentId);
    }

    public Optional<DiagnosisProposal> find(UUID proposalId) {
        return jdbcTemplate.query("SELECT proposal_json FROM diagnosis_proposal WHERE id=?",
                rs -> rs.next() ? Optional.of(read(rs.getString("proposal_json"))) : Optional.empty(), proposalId);
    }

    private DiagnosisProposal read(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, DiagnosisProposal.class);
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse diagnosis proposal", exception);
        }
    }
}
