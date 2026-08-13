package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.discovery.AgentDiscoveryProperties;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
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
    private final AgentDiscoveryProperties properties;
    private final DelegationFactory factory = new DelegationFactory();

    public DelegationCoordinator(AgentDelegationRepository repository, CapabilityRegistry capabilities,
                                 SpecialistTransport transport, EvidenceService evidenceService,
                                 AgentDiscoveryProperties properties) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.transport = transport;
        this.evidenceService = evidenceService;
        this.properties = properties;
    }

    public AgentDelegation execute(InvestigationRun run, int round, AgentType agentType, String objective,
                                   IncidentSnapshot incident, Instant deadline) {
        AgentDelegation created = factory.create(run, round, capabilities.requireAvailable(agentType), objective);
        AgentDelegation delegation = repository.insert(created) ? created
                : repository.findByIdempotencyKey(created.idempotencyKey()).orElseThrow();
        if (delegation.status().terminal()) {
            return delegation;
        }
        Instant taskDeadline = earliest(deadline, Instant.now().plusSeconds(properties.getTaskTimeoutSeconds()));
        try {
            delegation = recoverOrSubmit(delegation, incident, run, taskDeadline);
            while (!delegation.status().terminal() && Instant.now().isBefore(taskDeadline)) {
                pause(taskDeadline);
                delegation = pollOrResubmit(delegation, incident, run, taskDeadline);
            }
            if (!delegation.status().terminal()) {
                delegation = timeOut(delegation, run, taskDeadline);
            }
        } catch (RuntimeException exception) {
            delegation = repository.find(delegation.delegationId()).orElse(delegation);
            if (delegation.status().terminal()) {
                return delegation;
            }
            EvidenceReferenceArtifact failed = new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                    delegation.delegationId(), delegation.agentId(), delegation.capabilityVersion(),
                    DelegationStatus.FAILED, producedEvidenceIds(run, delegation), 0);
            repository.transition(delegation.delegationId(), delegation.status(), DelegationStatus.FAILED,
                    delegation.version(), delegation.remoteTaskId(), failed, exception.getClass().getSimpleName(),
                    safeMessage(exception), Instant.now());
        }
        return repository.find(delegation.delegationId()).orElseThrow();
    }

    private AgentDelegation recoverOrSubmit(AgentDelegation delegation, IncidentSnapshot incident,
                                             InvestigationRun run, Instant deadline) {
        if (delegation.status() == DelegationStatus.PENDING) {
            return submit(delegation, incident, run, deadline);
        }
        return pollOrResubmit(delegation, incident, run, deadline);
    }

    private AgentDelegation pollOrResubmit(AgentDelegation delegation, IncidentSnapshot incident,
                                            InvestigationRun run, Instant deadline) {
        java.util.Optional<A2aTaskSnapshot> remote = transport.query(delegation, deadline);
        return remote.isPresent() ? applySnapshot(delegation, remote.get(), run)
                : submit(delegation, incident, run, deadline);
    }

    private AgentDelegation submit(AgentDelegation delegation, IncidentSnapshot incident,
                                   InvestigationRun run, Instant deadline) {
        A2aTaskSnapshot snapshot = transport.submit(delegation, incident,
                evidenceService.findActiveByRun(run.runId()), deadline);
        validateSnapshot(delegation, snapshot);
        AgentDelegation current = repository.find(delegation.delegationId()).orElse(delegation);
        if (current.status() == DelegationStatus.PENDING) {
            repository.transition(current.delegationId(), DelegationStatus.PENDING, DelegationStatus.SUBMITTED,
                    current.version(), snapshot.remoteTaskId(), null, null, null, null);
            current = repository.find(current.delegationId()).orElseThrow();
        } else if (!snapshot.remoteTaskId().equals(current.remoteTaskId())) {
            repository.updateRemoteTaskId(current.delegationId(), current.status(), current.version(),
                    snapshot.remoteTaskId());
            current = repository.find(current.delegationId()).orElseThrow();
        }
        return applySnapshot(current, snapshot, run);
    }

    private AgentDelegation applySnapshot(AgentDelegation delegation, A2aTaskSnapshot snapshot,
                                           InvestigationRun run) {
        validateSnapshot(delegation, snapshot);
        AgentDelegation current = repository.find(delegation.delegationId()).orElse(delegation);
        if (current.status().terminal()) {
            return current;
        }
        if (!snapshot.remoteTaskId().equals(current.remoteTaskId())) {
            repository.updateRemoteTaskId(current.delegationId(), current.status(), current.version(),
                    snapshot.remoteTaskId());
            current = repository.find(current.delegationId()).orElseThrow();
        }
        if (snapshot.status() == DelegationStatus.SUBMITTED || snapshot.status() == current.status()) {
            return current;
        }
        if (snapshot.status() == DelegationStatus.RUNNING) {
            if (current.status() == DelegationStatus.SUBMITTED) {
                repository.transition(current.delegationId(), current.status(), DelegationStatus.RUNNING,
                        current.version(), snapshot.remoteTaskId(), null, null, null, null);
            }
            return repository.find(current.delegationId()).orElseThrow();
        }
        EvidenceReferenceArtifact artifact = snapshot.artifact();
        validateArtifact(current, artifact, evidenceService.findActiveByRun(run.runId()));
        repository.transition(current.delegationId(), current.status(), snapshot.status(), current.version(),
                snapshot.remoteTaskId(), artifact, snapshot.errorCode(), snapshot.errorMessage(), Instant.now());
        return repository.find(current.delegationId()).orElseThrow();
    }

    private AgentDelegation timeOut(AgentDelegation delegation, InvestigationRun run, Instant deadline) {
        try {
            transport.cancel(delegation, Instant.now().plusSeconds(properties.getRequestTimeoutSeconds()));
        } catch (RuntimeException ignored) {
            // Timeout remains authoritative even when best-effort remote cancellation fails.
        }
        AgentDelegation current = repository.find(delegation.delegationId()).orElse(delegation);
        EvidenceReferenceArtifact artifact = new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                current.delegationId(), current.agentId(), current.capabilityVersion(), DelegationStatus.TIMED_OUT,
                producedEvidenceIds(run, current), 0);
        repository.transition(current.delegationId(), current.status(), DelegationStatus.TIMED_OUT,
                current.version(), current.remoteTaskId(), artifact, "A2A_TASK_TIMEOUT",
                "Remote specialist task exceeded its deadline", Instant.now());
        return repository.find(current.delegationId()).orElseThrow();
    }

    private void validateSnapshot(AgentDelegation delegation, A2aTaskSnapshot snapshot) {
        if (!delegation.delegationId().equals(snapshot.taskId())) {
            throw new IllegalArgumentException("A2A task snapshot does not match the delegation");
        }
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

    private List<UUID> producedEvidenceIds(InvestigationRun run, AgentDelegation delegation) {
        return evidenceService.findActiveByRunAndTask(run.runId(), delegation.delegationId()).stream()
                .map(Evidence::evidenceId).distinct().toList();
    }

    private Instant earliest(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private void pause(Instant deadline) {
        long remaining = java.time.Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(properties.getPollIntervalMillis(), remaining));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A task polling was interrupted", exception);
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String value = message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
