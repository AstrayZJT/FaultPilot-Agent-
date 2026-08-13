package com.astrayzjt.faultpilot.evidence.api;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public final class RemoteEvidenceService {

    private final AgentDelegationRepository delegations;
    private final EvidenceService evidenceService;

    public RemoteEvidenceService(AgentDelegationRepository delegations, EvidenceService evidenceService) {
        this.delegations = delegations;
        this.evidenceService = evidenceService;
    }

    public RemoteEvidenceReceipt record(RemoteEvidenceWriteRequest request) {
        AgentDelegation delegation = requireActiveDelegation(request.taskId(), request.incidentId(), request.runId());
        if (!delegation.agentId().equals(request.agentId())
                || !delegation.capabilityVersion().equals(request.capabilityVersion())) {
            throw new IllegalArgumentException("Remote Evidence Agent identity does not match its delegation");
        }
        ToolResult result = new ToolResult(request.success(), request.summary(), request.structuredData(),
                request.evidenceType(), request.source());
        Evidence evidence = evidenceService.recordDelegated(request.runId(), request.incidentId(), request.taskId(),
                request.agentId(), request.capabilityVersion(), request.toolId(), request.toolCallId(), result,
                request.windowStart(), request.windowEnd());
        if (evidence == null) {
            throw new IllegalArgumentException("Remote Evidence must contain an Evidence type");
        }
        return new RemoteEvidenceReceipt(RemoteEvidenceReceipt.SCHEMA_VERSION, evidence.evidenceId(),
                EvidenceStatus.ACTIVE);
    }

    public List<RemoteEvidenceView> query(RemoteEvidenceQueryRequest request) {
        requireActiveDelegation(request.taskId(), request.incidentId(), request.runId());
        List<Evidence> active = evidenceService.findActiveByRun(request.runId());
        Set<UUID> requested = Set.copyOf(request.evidenceIds());
        Set<UUID> available = active.stream().map(Evidence::evidenceId).collect(Collectors.toSet());
        if (!available.containsAll(requested)) {
            throw new IllegalArgumentException("Evidence query references IDs outside the active investigation Run");
        }
        return active.stream().filter(item -> requested.contains(item.evidenceId()))
                .map(RemoteEvidenceView::from).toList();
    }

    private AgentDelegation requireActiveDelegation(UUID taskId, UUID incidentId, UUID runId) {
        AgentDelegation delegation = delegations.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Agent delegation"));
        if (!delegation.incidentId().equals(incidentId) || !delegation.runId().equals(runId)) {
            throw new IllegalArgumentException("Agent delegation does not belong to the supplied Incident and Run");
        }
        if (delegation.status().terminal()) {
            throw new IllegalArgumentException("Terminal Agent delegation cannot access remote Evidence");
        }
        return delegation;
    }
}
