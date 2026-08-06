package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record PendingAction(
        UUID id,
        UUID incidentId,
        ActionCode actionCode,
        RiskLevel riskLevel,
        Map<String, Object> parameters,
        String argumentsHash,
        PendingActionStatus status,
        String idempotencyKey,
        Instant expiresAt,
        String confirmedBy,
        Instant confirmedAt,
        Instant startedAt,
        Instant executedAt,
        Map<String, Object> result,
        String errorCode,
        String errorMessage,
        long version) {
}
