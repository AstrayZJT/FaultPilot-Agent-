package com.astrayzjt.faultpilot.agent.jvm.protocol;

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
        requireText(idempotencyKey, "idempotencyKey", 320);
        requireText(capabilityVersion, "capabilityVersion", 128);
        requireText(objective, "objective", 2_000);
        availableEvidenceIds = availableEvidenceIds == null ? List.of()
                : availableEvidenceIds.stream().distinct().toList();
        if (availableEvidenceIds.size() > 100) {
            throw new IllegalArgumentException("Delegation supports at most 100 Evidence IDs");
        }
    }

    public record IncidentContext(UUID incidentId, UUID runId, String serviceName, String symptom,
                                  TimeRange timeRange) {
        public IncidentContext {
            if (incidentId == null || runId == null || timeRange == null) {
                throw new IllegalArgumentException("Invalid delegation Incident context");
            }
            requireText(serviceName, "serviceName", 128);
            requireText(symptom, "symptom", 2_000);
        }
    }

    public record Limits(int maxSteps, Instant deadline) {
        public Limits {
            if (maxSteps < 1 || maxSteps > 10 || deadline == null) {
                throw new IllegalArgumentException("Invalid delegation limits");
            }
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
    }
}
