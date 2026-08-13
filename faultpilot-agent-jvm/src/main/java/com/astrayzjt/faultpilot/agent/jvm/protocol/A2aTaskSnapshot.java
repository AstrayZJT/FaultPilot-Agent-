package com.astrayzjt.faultpilot.agent.jvm.protocol;

import java.time.Instant;
import java.util.UUID;

public record A2aTaskSnapshot(
        String schemaVersion,
        String remoteTaskId,
        UUID taskId,
        TaskStatus status,
        EvidenceReferenceArtifact artifact,
        String errorCode,
        String errorMessage,
        Instant updatedAt) {

    public static final String SCHEMA_VERSION = "faultpilot.a2a-task/v1";
}
