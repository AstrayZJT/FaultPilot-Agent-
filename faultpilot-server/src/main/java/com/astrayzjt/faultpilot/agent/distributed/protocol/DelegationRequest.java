package com.astrayzjt.faultpilot.agent.distributed.protocol;

import com.astrayzjt.faultpilot.common.domain.TimeRange;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DelegationRequest(
        String schemaVersion,
        UUID taskId,
        String idempotencyKey,
        String capabilityVersion,
        IncidentContext incident,
        String objective,
        List<UUID> availableEvidenceIds,
        Limits limits) {

    public static final String SCHEMA_VERSION = "faultpilot.delegation/v1";

    public DelegationRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion) || taskId == null || incident == null || limits == null) {
            throw new IllegalArgumentException("Invalid delegation request envelope");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || capabilityVersion == null
                || capabilityVersion.isBlank() || objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("Delegation identity, capability version and objective are required");
        }
        availableEvidenceIds = availableEvidenceIds == null ? List.of()
                : availableEvidenceIds.stream().distinct().toList();
    }

    public record IncidentContext(
            UUID incidentId,
            UUID runId,
            String serviceName,
            String symptom,
            TimeRange timeRange) {

        public IncidentContext {
            if (incidentId == null || runId == null || serviceName == null || serviceName.isBlank()
                    || symptom == null || symptom.isBlank() || timeRange == null) {
                throw new IllegalArgumentException("Invalid delegation Incident context");
            }
        }
    }

    public record Limits(int maxSteps, Instant deadline) {
        public Limits {
            if (maxSteps < 1 || maxSteps > 10 || deadline == null) {
                throw new IllegalArgumentException("Delegation limits are invalid");
            }
        }
    }
}
