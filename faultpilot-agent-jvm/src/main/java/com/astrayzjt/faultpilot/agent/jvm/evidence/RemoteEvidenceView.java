package com.astrayzjt.faultpilot.agent.jvm.evidence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemoteEvidenceView(UUID evidenceId, String evidenceType, String source, String summary,
                                  Map<String, Object> structuredData, Instant windowStart, Instant windowEnd) {
    public RemoteEvidenceView {
        evidenceType = evidenceType == null ? "" : evidenceType.trim();
        structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    }

    public boolean hasType(com.astrayzjt.faultpilot.agent.jvm.diagnostic.EvidenceType type) {
        return type != null && type.name().equals(evidenceType);
    }
}
