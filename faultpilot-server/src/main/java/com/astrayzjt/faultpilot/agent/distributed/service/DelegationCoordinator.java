package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.agent.distributed.transport.SpecialistTransport;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public final class DelegationCoordinator {

    private final AgentDelegationRepository repository;
    private final CapabilityRegistry capabilities;
    private final SpecialistTransport transport;
    private final EvidenceService evidenceService;
    private final DelegationFactory factory = new DelegationFactory();

    public DelegationCoordinator(AgentDelegationRepository repository, CapabilityRegistry capabilities,
                                 SpecialistTransport transport, EvidenceService evidenceService) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.transport = transport;
        this.evidenceService = evidenceService;
    }

    public AgentDelegation execute(InvestigationRun run, int round, AgentType agentType, String objective,
                                   IncidentSnapshot incident, Instant deadline) {
        AgentDelegation created = factory.create(run, round, capabilities.requireAvailable(agentType), objective);
        AgentDelegation delegation = repository.insert(created) ? created
                : repository.findByIdempotencyKey(created.idempotencyKey()).orElseThrow();
        if (delegation.status().terminal()) {
            return delegation;
        }
        if (delegation.status() == DelegationStatus.PENDING) {
            repository.transition(delegation.delegationId(), DelegationStatus.PENDING, DelegationStatus.RUNNING,
                    delegation.version(), null, null, null, null, null);
            delegation = repository.find(delegation.delegationId()).orElseThrow();
        }
        try {
            List<Evidence> evidence = evidenceService.findActiveByRun(run.runId());
            EvidenceReferenceArtifact artifact = transport.execute(delegation, incident, evidence, deadline);
            validateArtifact(delegation, artifact, evidenceService.findActiveByRun(run.runId()));
            repository.transition(delegation.delegationId(), delegation.status(), artifact.executionStatus(),
                    delegation.version(), delegation.remoteTaskId(), artifact, null, null, Instant.now());
        } catch (RuntimeException exception) {
            EvidenceReferenceArtifact failed = new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                    delegation.delegationId(), delegation.agentId(), delegation.capabilityVersion(),
                    DelegationStatus.FAILED, List.of(), 0);
            repository.transition(delegation.delegationId(), delegation.status(), DelegationStatus.FAILED,
                    delegation.version(), delegation.remoteTaskId(), failed, exception.getClass().getSimpleName(),
                    safeMessage(exception), Instant.now());
        }
        return repository.find(delegation.delegationId()).orElseThrow();
    }

    private void validateArtifact(AgentDelegation delegation, EvidenceReferenceArtifact artifact,
                                  List<Evidence> activeEvidence) {
        if (!delegation.delegationId().equals(artifact.taskId())
                || !delegation.agentId().equals(artifact.agentId())
                || !delegation.capabilityVersion().equals(artifact.capabilityVersion())) {
            throw new IllegalArgumentException("Specialist artifact identity does not match the delegation");
        }
        Set<UUID> allowed = activeEvidence.stream().map(Evidence::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!allowed.containsAll(artifact.evidenceIds())) {
            throw new IllegalArgumentException("Specialist artifact references Evidence outside the active Run");
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String value = message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
