package com.astrayzjt.faultpilot.incident.persistence;

import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

@Repository
public class IncidentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public IncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void insert(Incident incident, String rawRequestJson) {
        insert(incident, rawRequestJson, "MANUAL", null);
    }

    public void insert(Incident incident, String rawRequestJson, String source, String externalRef) {
        try {
            IncidentSnapshot snapshot = incident.snapshot();
            jdbcTemplate.update("INSERT INTO incident_run " +
                            "(id,status,service_name,symptom,alert_id,start_time,end_time,endpoint_name," +
                            "instance_name,request_id,allow_remediation,source,external_ref,raw_request_json,normalized_snapshot_json,created_at,updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                    incident.incidentId(), incident.status().name(), snapshot.serviceName(), snapshot.symptom(),
                    snapshot.alertId(), Timestamp.from(snapshot.timeRange().start()), Timestamp.from(snapshot.timeRange().end()),
                    snapshot.endpointName(), snapshot.instanceName(), snapshot.requestId(), snapshot.allowRemediation(), source, externalRef, rawRequestJson,
                    json(snapshot), Timestamp.from(incident.createdAt()), Timestamp.from(incident.updatedAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize incident snapshot", exception);
        }
    }

    public Optional<Incident> findById(UUID incidentId) {
        List<Incident> incidents = jdbcTemplate.query("SELECT id,status,service_name,symptom,alert_id,start_time,end_time," +
                        "endpoint_name,instance_name,request_id,allow_remediation,created_at,updated_at " +
                        "FROM incident_run WHERE id=?", (rs, row) -> {
                    Instant start = rs.getTimestamp("start_time").toInstant();
                    Instant end = rs.getTimestamp("end_time").toInstant();
                    IncidentSnapshot snapshot = new IncidentSnapshot(rs.getObject("id", UUID.class),
                            rs.getString("service_name"), rs.getString("symptom"), rs.getString("alert_id"),
                            new TimeRange(start, end), rs.getString("endpoint_name"), rs.getString("instance_name"),
                            rs.getString("request_id"), rs.getBoolean("allow_remediation"), rs.getTimestamp("updated_at").toInstant());
                    return new Incident(snapshot.incidentId(), IncidentStatus.valueOf(rs.getString("status")), snapshot,
                            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
                }, incidentId);
        return incidents.stream().findFirst();
    }

    public void updateStatus(UUID incidentId, IncidentStatus status) {
        jdbcTemplate.update("UPDATE incident_run SET status=?, updated_at=CURRENT_TIMESTAMP, version=version+1 WHERE id=?",
                status.name(), incidentId);
    }

    public Optional<UUID> findIdByExternalRef(String source, String externalRef) {
        return jdbcTemplate.query("SELECT id FROM incident_run WHERE source=? AND external_ref=?", 
                (rs, row) -> rs.getObject("id", UUID.class), source, externalRef).stream().findFirst();
    }

    public List<UUID> findIdsByStatus(Set<IncidentStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(statuses.size(), "?"));
        Object[] arguments = statuses.stream().map(Enum::name).toArray();
        return jdbcTemplate.query("SELECT id FROM incident_run WHERE status IN (" + placeholders + ") ORDER BY created_at",
                (rs, row) -> rs.getObject("id", UUID.class), arguments);
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
