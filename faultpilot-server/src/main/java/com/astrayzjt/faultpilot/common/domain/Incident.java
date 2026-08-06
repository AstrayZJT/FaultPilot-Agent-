package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.UUID;

public record Incident(
        UUID incidentId,
        IncidentStatus status,
        IncidentSnapshot snapshot,
        Instant createdAt,
        Instant updatedAt) {
}

