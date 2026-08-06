package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.UUID;

public record IncidentSnapshot(
        UUID incidentId,
        String serviceName,
        String symptom,
        String alertId,
        TimeRange timeRange,
        String endpointName,
        String instanceName,
        String requestId,
        boolean allowRemediation,
        Instant normalizedAt) {
}

