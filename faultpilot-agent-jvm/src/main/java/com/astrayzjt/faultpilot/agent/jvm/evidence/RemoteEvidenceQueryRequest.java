package com.astrayzjt.faultpilot.agent.jvm.evidence;

import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;

import java.util.List;
import java.util.UUID;

public record RemoteEvidenceQueryRequest(String schemaVersion, UUID incidentId, UUID runId, UUID taskId,
                                         List<UUID> evidenceIds) {

    public static final String SCHEMA_VERSION = "faultpilot.evidence-query/v1";

    static RemoteEvidenceQueryRequest from(DelegationRequest task, List<UUID> evidenceIds) {
        return new RemoteEvidenceQueryRequest(SCHEMA_VERSION, task.incident().incidentId(),
                task.incident().runId(), task.taskId(), evidenceIds);
    }
}
