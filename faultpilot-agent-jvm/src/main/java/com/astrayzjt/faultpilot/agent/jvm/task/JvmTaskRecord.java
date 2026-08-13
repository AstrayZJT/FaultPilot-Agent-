package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JvmTaskRecord(
        UUID remoteTaskId,
        UUID taskId,
        String idempotencyKey,
        DelegationRequest request,
        TaskStatus status,
        String selectedSkill,
        List<UUID> evidenceIds,
        int stepsUsed,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public JvmTaskRecord {
        selectedSkill = selectedSkill == null ? "" : selectedSkill.trim();
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public A2aTaskSnapshot snapshot(JvmAgentProperties properties) {
        EvidenceReferenceArtifact artifact = status.terminal()
                ? new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION, taskId,
                properties.getAgentId(), request.capabilityVersion(), status, evidenceIds, stepsUsed)
                : null;
        return new A2aTaskSnapshot(A2aTaskSnapshot.SCHEMA_VERSION, remoteTaskId.toString(), taskId,
                status, artifact, errorCode, errorMessage, updatedAt);
    }
}
