package com.astrayzjt.faultpilot.evidence.api;

import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemoteEvidenceWriteRequest(
        String schemaVersion,
        UUID incidentId,
        UUID runId,
        UUID taskId,
        String agentId,
        String capabilityVersion,
        String toolId,
        String toolCallId,
        boolean success,
        EvidenceType evidenceType,
        String source,
        String summary,
        Map<String, Object> structuredData,
        Instant windowStart,
        Instant windowEnd) {

    public static final String SCHEMA_VERSION = "faultpilot.remote-evidence/v1";

    public RemoteEvidenceWriteRequest {
        if (!SCHEMA_VERSION.equals(schemaVersion) || incidentId == null || runId == null || taskId == null
                || evidenceType == null || windowStart == null || windowEnd == null || windowStart.isAfter(windowEnd)) {
            throw new IllegalArgumentException("Invalid remote Evidence envelope");
        }
        requireText(agentId, "agentId", 128);
        requireText(capabilityVersion, "capabilityVersion", 128);
        requireText(toolId, "toolId", 192);
        requireText(toolCallId, "toolCallId", 256);
        requireText(source, "source", 256);
        requireText(summary, "summary", 2_000);
        structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
    }
}
