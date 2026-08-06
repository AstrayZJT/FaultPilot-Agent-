package com.astrayzjt.faultpilot.incident.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record IncidentEvent(long id, UUID incidentId, String eventType, JsonNode payload, Instant createdAt) {
}
