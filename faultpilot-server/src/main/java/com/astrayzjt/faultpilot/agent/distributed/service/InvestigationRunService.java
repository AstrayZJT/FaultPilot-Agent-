package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InvestigationRunService {

    private final InvestigationRunRepository repository;
    private final CapabilityRegistry capabilities;

    public InvestigationRunService(InvestigationRunRepository repository, CapabilityRegistry capabilities) {
        this.repository = repository;
        this.capabilities = capabilities;
    }

    public InvestigationRun getOrCreate(UUID incidentId) {
        return repository.findActiveByIncident(incidentId).orElseGet(() -> create(incidentId));
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
