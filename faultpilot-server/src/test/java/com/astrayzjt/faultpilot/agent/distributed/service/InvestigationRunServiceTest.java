package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InvestigationRunServiceTest {

    @Test
    void reusesActiveRunWithoutCreatingAnother() {
        InvestigationRunRepository repository = mock(InvestigationRunRepository.class);
        UUID incidentId = UUID.randomUUID();
        InvestigationRun existing = InvestigationRun.pending(incidentId, UUID.randomUUID(), Instant.now());
        when(repository.findActiveByIncident(incidentId)).thenReturn(Optional.of(existing));
        InvestigationRunService service = new InvestigationRunService(repository, registry());

        assertThat(service.getOrCreate(incidentId)).isSameAs(existing);
        verify(repository, never()).insert(any());
    }

    @Test
    void bindsNewRunToCurrentCapabilitySnapshotAndRecoversConcurrentInsert() {
        InvestigationRunRepository repository = mock(InvestigationRunRepository.class);
        CapabilityRegistry registry = registry();
        UUID incidentId = UUID.randomUUID();
        InvestigationRun concurrent = InvestigationRun.pending(incidentId,
                registry.currentSnapshot().snapshotId(), Instant.now());
        when(repository.findActiveByIncident(incidentId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrent));
        doThrow(new DuplicateKeyException("active run exists")).when(repository).insert(any());
        InvestigationRunService service = new InvestigationRunService(repository, registry);

        InvestigationRun result = service.getOrCreate(incidentId);

        assertThat(result).isSameAs(concurrent);
        verify(repository).insert(any(InvestigationRun.class));
    }

    private CapabilityRegistry registry() {
        CapabilityRegistry registry = new CapabilityRegistry();
        AgentCapability capability = new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent",
                "Investigates JVM faults", URI.create("local://jvm-agent"), "1.0", "jvm-1.0.0",
                AgentAvailability.AVAILABLE, false);
        registry.initialize(new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.ACTIVE,
                List.of(capability), Instant.now()));
        return registry;
    }
}
