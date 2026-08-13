package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InvestigationRunService {

    private final InvestigationRunRepository repository;
    private final CapabilityRegistry capabilities;
    private final CapabilitySnapshotRepository snapshotRepository;
    private final AgentDelegationRepository delegationRepository;
    private final EvidenceService evidenceService;

    public InvestigationRunService(InvestigationRunRepository repository, CapabilityRegistry capabilities,
                                   CapabilitySnapshotRepository snapshotRepository,
                                   AgentDelegationRepository delegationRepository,
                                   EvidenceService evidenceService) {
        this.repository = repository;
        this.capabilities = capabilities;
        this.snapshotRepository = snapshotRepository;
        this.delegationRepository = delegationRepository;
        this.evidenceService = evidenceService;
    }

    public InvestigationRun getOrCreate(UUID incidentId) {
        return repository.findActiveByIncident(incidentId)
                .map(this::reuseOrRestart)
                .orElseGet(() -> create(incidentId));
    }

    private InvestigationRun reuseOrRestart(InvestigationRun active) {
        var boundSnapshot = snapshotRepository.find(active.capabilitySnapshotId()).orElseThrow();
        if (boundSnapshot.sameCapabilities(capabilities.currentSnapshot())) {
            return active;
        }
        boolean stale = repository.transition(active.runId(), active.status(), InvestigationRunStatus.STALE,
                active.version(), "CAPABILITY_CHANGED", Instant.now());
        if (!stale) {
            return repository.findActiveByIncident(active.incidentId())
                    .map(this::reuseOrRestart)
                    .orElseGet(() -> create(active.incidentId()));
        }
        evidenceService.markRunStale(active.runId());
        delegationRepository.markRecoverableStale(active.runId());
        return create(active.incidentId());
    }

    private InvestigationRun create(UUID incidentId) {
        InvestigationRun created = InvestigationRun.pending(incidentId,
                capabilities.currentSnapshot().snapshotId(), Instant.now());
        try {
            repository.insert(created);
            return created;
        } catch (DuplicateKeyException exception) {
            return repository.findActiveByIncident(incidentId).orElseThrow(() -> exception);
        }
    }
}
