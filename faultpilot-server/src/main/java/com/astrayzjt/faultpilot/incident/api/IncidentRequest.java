package com.astrayzjt.faultpilot.incident.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record IncidentRequest(
        @NotBlank String serviceName,
        String symptom,
        String alertId,
        Instant startTime,
        Instant endTime,
        String endpointName,
        String instanceName,
        String requestId,
        Boolean allowRemediation) {

    @AssertTrue(message = "symptom or alertId must be provided")
    public boolean hasIncidentSignal() {
        return (symptom != null && !symptom.isBlank()) || (alertId != null && !alertId.isBlank());
    }
}

