package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Evidence(
        UUID evidenceId,
        UUID incidentId,
        UUID producerTaskId,
        UUID runId,
        String agentId,
        String toolId,
        String toolCallId,
        String capabilityVersion,
        EvidenceStatus status,
        EvidenceType type,
        String source,
        String entity,
        Instant windowStart,
        Instant windowEnd,
        String summary,
        String rawDataReference,
        String contentHash,
        Map<String, Object> structuredData,
        Instant collectedAt) {

    public Evidence {
        status = status == null ? EvidenceStatus.ACTIVE : status;
        structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    }

    public Evidence(UUID evidenceId, UUID incidentId, UUID producerTaskId, EvidenceType type,
                    String source, String entity, Instant windowStart, Instant windowEnd, String summary,
                    String rawDataReference, String contentHash, Instant collectedAt) {
        this(evidenceId, incidentId, producerTaskId, null, null, null, null, null,
                EvidenceStatus.ACTIVE, type, source, entity, windowStart, windowEnd, summary,
                rawDataReference, contentHash, Map.of(), collectedAt);
    }
}
