package com.astrayzjt.faultpilot.agent.jvm.evidence;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.DiagnosticObservation;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;

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
        com.astrayzjt.faultpilot.agent.jvm.diagnostic.EvidenceType evidenceType,
        String source,
        String summary,
        Map<String, Object> structuredData,
        Instant windowStart,
        Instant windowEnd) {

    public static final String SCHEMA_VERSION = "faultpilot.remote-evidence/v1";

    static RemoteEvidenceWriteRequest from(DelegationRequest task, String agentId, String toolId,
                                           String toolCallId, DiagnosticObservation observation) {
        return new RemoteEvidenceWriteRequest(SCHEMA_VERSION, task.incident().incidentId(),
                task.incident().runId(), task.taskId(), agentId, task.capabilityVersion(), toolId, toolCallId,
                observation.success(), observation.evidenceType(), observation.source(), observation.summary(),
                observation.data(), task.incident().timeRange().start(), task.incident().timeRange().end());
    }
}
