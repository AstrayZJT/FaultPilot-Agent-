package com.astrayzjt.faultpilot.evidence.api;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemoteEvidenceView(
        UUID evidenceId,
        EvidenceType evidenceType,
        String source,
        String summary,
        Map<String, Object> structuredData,
        Instant windowStart,
        Instant windowEnd) {

    static RemoteEvidenceView from(Evidence evidence) {
        return new RemoteEvidenceView(evidence.evidenceId(), evidence.type(), evidence.source(), evidence.summary(),
                evidence.structuredData(), evidence.windowStart(), evidence.windowEnd());
    }
}
