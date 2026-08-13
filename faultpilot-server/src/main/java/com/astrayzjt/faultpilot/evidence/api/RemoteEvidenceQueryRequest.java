package com.astrayzjt.faultpilot.evidence.api;

import java.util.List;
import java.util.UUID;

public record RemoteEvidenceQueryRequest(
        String schemaVersion,
        UUID incidentId,
        UUID runId,
        UUID taskId,
        List<UUID> evidenceIds) {

    public static final String SCHEMA_VERSION = "faultpilot.evidence-query/v1";

    public RemoteEvidenceQueryRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion) || incidentId == null || runId == null || taskId == null) {
            throw new IllegalArgumentException("Invalid Evidence query envelope");
        }
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        if (evidenceIds.size() > 100 || evidenceIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Evidence query supports at most 100 valid IDs");
        }
    }
}
