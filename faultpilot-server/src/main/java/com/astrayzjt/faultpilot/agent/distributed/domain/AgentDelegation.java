package com.astrayzjt.faultpilot.agent.distributed.domain;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;

import java.time.Instant;
import java.util.UUID;

public record AgentDelegation(
        UUID delegationId,
        UUID runId,
        UUID incidentId,
        int round,
        String agentId,
        AgentType agentType,
        String capabilityVersion,
        String objective,
        String objectiveHash,
        String idempotencyKey,
        String remoteTaskId,
        DelegationStatus status,
        EvidenceReferenceArtifact artifact,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage,
        long version) {

    public AgentDelegation {
        if (delegationId == null || runId == null || incidentId == null || round < 1 || agentType == null
                || status == null || version < 0) {
            throw new IllegalArgumentException("Invalid Agent delegation identity or state");
        }
        requireText(agentId, "agentId", 128);
        requireText(capabilityVersion, "capabilityVersion", 128);
        requireText(objective, "objective", 2_000);
        requireText(objectiveHash, "objectiveHash", 64);
        requireText(idempotencyKey, "idempotencyKey", 320);
        if (status.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("Terminal delegation must have completedAt and active delegation must not");
        }
        if (status == DelegationStatus.COMPLETED && artifact == null) {
            throw new IllegalArgumentException("Completed delegation must contain an Evidence artifact");
        }
        if (artifact != null && !delegationId.equals(artifact.taskId())) {
            throw new IllegalArgumentException("Delegation artifact taskId does not match delegationId");
        }
        if (artifact != null && (!agentId.equals(artifact.agentId())
                || !capabilityVersion.equals(artifact.capabilityVersion())
                || status != artifact.executionStatus())) {
            throw new IllegalArgumentException("Delegation artifact identity or status does not match delegation");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
    }
}
