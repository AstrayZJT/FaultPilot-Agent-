package com.astrayzjt.faultpilot.incident.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class IncidentEventService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public IncidentEventService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public long append(UUID incidentId, String eventType, Object payload) {
        try {
            Long id = jdbcTemplate.queryForObject("INSERT INTO incident_event " +
                            "(incident_id,event_type,payload_json,created_at) VALUES (?,?,?::jsonb,?) RETURNING id",
                    Long.class, incidentId, eventType, objectMapper.writeValueAsString(payload == null ? Map.of() : payload),
                    Timestamp.from(Instant.now()));
            long eventId = id == null ? 0 : id;
            eventPublisher.publishEvent(new IncidentEvent(eventId, incidentId, eventType,
                    objectMapper.readTree(objectMapper.writeValueAsString(payload == null ? Map.of() : payload)), Instant.now()));
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize incident event", exception);
        }
    }

    public List<IncidentEvent> findAfter(UUID incidentId, long afterId) {
        return jdbcTemplate.query("SELECT id,incident_id,event_type,payload_json,created_at FROM incident_event " +
                        "WHERE incident_id=? AND id>? ORDER BY id LIMIT 500", (rs, row) ->
                        new IncidentEvent(rs.getLong("id"), rs.getObject("incident_id", UUID.class),
                                rs.getString("event_type"), readTree(rs.getString("payload_json")),
                                rs.getTimestamp("created_at").toInstant()), incidentId, afterId);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }
}
