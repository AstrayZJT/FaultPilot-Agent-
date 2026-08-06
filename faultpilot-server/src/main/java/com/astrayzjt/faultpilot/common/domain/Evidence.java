package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.UUID;

public record Evidence(
        UUID evidenceId,
        UUID incidentId,
        UUID producerTaskId,
        EvidenceType type,
        String source,
        String entity,
        Instant windowStart,
        Instant windowEnd,
        String summary,
        String rawDataReference,
        String contentHash,
        Instant collectedAt) {
}

