package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentTaskStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.FindingStatus;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentTaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "faultpilot.agents", name = "transport", havingValue = "LOCAL", matchIfMissing = true)
public final class LocalSpecialistTransport implements SpecialistTransport {

    private static final int DEFAULT_MAX_STEPS = 4;

    private final Map<AgentType, SpecialistAgent> agents;
    private final AgentTaskRepository taskRepository;
    private final EvidenceService evidenceService;

    public LocalSpecialistTransport(List<SpecialistAgent> agents, AgentTaskRepository taskRepository,
                                    EvidenceService evidenceService) {
        EnumMap<AgentType, SpecialistAgent> indexed = new EnumMap<>(AgentType.class);
        for (SpecialistAgent agent : agents) {
            if (indexed.put(agent.type(), agent) != null) {
                throw new IllegalStateException("Duplicate local SpecialistAgent: " + agent.type());
            }
        }
        this.agents = Map.copyOf(indexed);
        this.taskRepository = taskRepository;
        this.evidenceService = evidenceService;
    }

    @Override
    public EvidenceReferenceArtifact execute(AgentDelegation delegation, IncidentSnapshot incident,
                                             List<Evidence> activeEvidence, Instant deadline) {
        if (Instant.now().isAfter(deadline)) {
            return artifact(delegation, DelegationStatus.TIMED_OUT, List.of(), 0);
        }
        SpecialistAgent agent = agents.get(delegation.agentType());
        if (agent == null) {
            return artifact(delegation, DelegationStatus.FAILED, List.of(), 0);
        }
        AgentTask task = task(delegation);
        taskRepository.insert(task);
        AgentFinding finding = taskRepository.findFinding(task.taskId()).orElseGet(() -> investigate(agent, task,
                incident, activeEvidence));
        LinkedHashSet<java.util.UUID> evidenceIds = new LinkedHashSet<>();
        evidenceIds.addAll(finding.supportingEvidenceIds());
        evidenceIds.addAll(finding.counterEvidenceIds());
        evidenceIds.addAll(evidenceService.findActiveByRunAndTask(delegation.runId(), delegation.delegationId())
                .stream().map(Evidence::evidenceId).toList());
        DelegationStatus status = status(finding.status(), evidenceIds.isEmpty());
        return artifact(delegation, status, List.copyOf(evidenceIds), finding.stepsUsed());
    }

    private AgentFinding investigate(SpecialistAgent agent, AgentTask task, IncidentSnapshot incident,
                                     List<Evidence> activeEvidence) {
        taskRepository.markRunning(task);
        try {
            AgentFinding finding = agent.investigate(task, incident, activeEvidence);
            taskRepository.complete(task, taskStatus(finding.status()), finding, null);
            return finding;
        } catch (RuntimeException exception) {
            taskRepository.complete(task, AgentTaskStatus.FAILED, null, safeMessage(exception));
            throw exception;
        }
    }

    private AgentTask task(AgentDelegation delegation) {
        return new AgentTask(delegation.delegationId(), delegation.incidentId(),
                delegation.agentId() + "-round-" + delegation.round(), delegation.agentType(),
                delegation.objective(), DEFAULT_MAX_STEPS, delegation.round(), AgentTaskStatus.PENDING,
                null, null, delegation.runId(), delegation.agentId(), delegation.capabilityVersion());
    }

    private DelegationStatus status(FindingStatus status, boolean noEvidence) {
        return switch (status) {
            case SUCCEEDED -> noEvidence ? DelegationStatus.INSUFFICIENT : DelegationStatus.COMPLETED;
            case OUT_OF_SCOPE, INSUFFICIENT_EVIDENCE -> DelegationStatus.INSUFFICIENT;
            case TIMED_OUT -> DelegationStatus.TIMED_OUT;
            case FAILED -> DelegationStatus.FAILED;
        };
    }

    private AgentTaskStatus taskStatus(FindingStatus status) {
        return switch (status) {
            case SUCCEEDED -> AgentTaskStatus.SUCCEEDED;
            case OUT_OF_SCOPE -> AgentTaskStatus.OUT_OF_SCOPE;
            case INSUFFICIENT_EVIDENCE -> AgentTaskStatus.INSUFFICIENT_EVIDENCE;
            case TIMED_OUT -> AgentTaskStatus.TIMED_OUT;
            case FAILED -> AgentTaskStatus.FAILED;
        };
    }

    private EvidenceReferenceArtifact artifact(AgentDelegation delegation, DelegationStatus status,
                                               List<java.util.UUID> evidenceIds, int stepsUsed) {
        return new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                delegation.delegationId(), delegation.agentId(), delegation.capabilityVersion(), status,
                evidenceIds, Math.max(0, Math.min(10, stepsUsed)));
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String value = message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
