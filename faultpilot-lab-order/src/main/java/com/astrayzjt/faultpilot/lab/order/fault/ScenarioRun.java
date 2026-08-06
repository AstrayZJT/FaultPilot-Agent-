package com.astrayzjt.faultpilot.lab.order.fault;

import java.time.Instant;
import java.util.UUID;

public record ScenarioRun(
        UUID scenarioRunId,
        ScenarioCode scenarioCode,
        String targetService,
        String status,
        Instant injectedAt,
        Instant expiresAt,
        Instant recoveredAt,
        String startedBy,
        String errorMessage) {
}

