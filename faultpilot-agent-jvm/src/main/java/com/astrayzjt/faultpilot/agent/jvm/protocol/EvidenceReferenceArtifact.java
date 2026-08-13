package com.astrayzjt.faultpilot.agent.jvm.protocol;

import java.util.List;
import java.util.UUID;

public record EvidenceReferenceArtifact(
        String schemaVersion,
        UUID taskId,
        String agentId,
        String capabilityVersion,
        TaskStatus executionStatus,
        List<UUID> evidenceIds,
        int stepsUsed) {

    public static final String SCHEMA_VERSION = "faultpilot.evidence-refs/v1";

    public EvidenceReferenceArtifact {
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        if (!SCHEMA_VERSION.equals(schemaVersion) || taskId == null || agentId == null || agentId.isBlank()
                || capabilityVersion == null || capabilityVersion.isBlank() || executionStatus == null
                || !executionStatus.terminal() || stepsUsed < 0 || stepsUsed > 10) {
            throw new IllegalArgumentException("Invalid Evidence reference artifact");
        }
        if (executionStatus == TaskStatus.COMPLETED && evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("Completed task must reference Evidence");
        }
    }
}
