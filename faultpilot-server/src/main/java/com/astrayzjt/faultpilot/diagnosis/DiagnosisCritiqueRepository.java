package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class DiagnosisCritiqueRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DiagnosisCritiqueRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(DiagnosisCritique critique) {
        try {
            jdbcTemplate.update("INSERT INTO diagnosis_critique (id,proposal_id,verdict,critique_json,created_at) VALUES (?,?,?,?,?)",
                    critique.critiqueId(), critique.proposalId(), critique.verdict().name(),
                    objectMapper.writeValueAsString(critique), Timestamp.from(Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot persist diagnosis critique", exception);
        }
    }

    public List<DiagnosisCritique> findByProposal(UUID proposalId) {
        return jdbcTemplate.query("SELECT critique_json FROM diagnosis_critique WHERE proposal_id=? ORDER BY created_at",
                (rs, row) -> read(rs.getString("critique_json")), proposalId);
    }

    private DiagnosisCritique read(String json) throws java.sql.SQLException {
        try {
            return objectMapper.readValue(json, DiagnosisCritique.class);
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Cannot parse diagnosis critique", exception);
        }
    }
}
