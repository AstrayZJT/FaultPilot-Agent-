package com.astrayzjt.faultpilot.agent.distributed.protocol;

import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;

import java.time.Instant;
import java.util.UUID;

public record A2aTaskSnapshot(
        String schemaVersion,
        String remoteTaskId,
        UUID taskId,
        DelegationStatus status,
        EvidenceReferenceArtifact artifact,
        String errorCode,
        String errorMessage,
        Instant updatedAt) {

    public static final String SCHEMA_VERSION = "faultpilot.a2a-task/v1";

    public A2aTaskSnapshot {
        if (!SCHEMA_VERSION.equals(schemaVersion) || taskId == null || status == null || updatedAt == null
                || remoteTaskId == null || remoteTaskId.isBlank() || remoteTaskId.length() > 256) {
            throw new IllegalArgumentException("Invalid A2A task snapshot");
        }
        if (status == DelegationStatus.PENDING || status == DelegationStatus.STALE) {
            throw new IllegalArgumentException("Remote A2A task cannot expose local-only status " + status);
        }
        if (status.terminal() != (artifact != null)) {
            throw new IllegalArgumentException("Terminal A2A task must contain an artifact");
        }
        if (artifact != null && (!taskId.equals(artifact.taskId()) || status != artifact.executionStatus())) {
            throw new IllegalArgumentException("A2A task artifact does not match the task snapshot");
        }
        if (errorCode != null && errorCode.length() > 64) {
            throw new IllegalArgumentException("A2A task errorCode must not exceed 64 characters");
        }
        if (errorMessage != null && errorMessage.length() > 500) {
            throw new IllegalArgumentException("A2A task errorMessage must not exceed 500 characters");
        }
    }
}
