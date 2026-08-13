package com.astrayzjt.faultpilot.agent.jvm.evidence;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.EvidenceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemoteEvidenceView(UUID evidenceId, EvidenceType evidenceType, String source, String summary,
                                 Map<String, Object> structuredData, Instant windowStart, Instant windowEnd) {
    public RemoteEvidenceView {
        structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    }
}
