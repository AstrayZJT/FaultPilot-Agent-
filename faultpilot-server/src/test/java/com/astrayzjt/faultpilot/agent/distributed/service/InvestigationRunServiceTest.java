package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
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
        CapabilityRegistry registry = registry();
        CapabilitySnapshotRepository snapshots = mock(CapabilitySnapshotRepository.class);
        when(snapshots.find(existing.capabilitySnapshotId())).thenReturn(Optional.of(registry.currentSnapshot()));
        InvestigationRunService service = new InvestigationRunService(repository, registry, snapshots,
                mock(AgentDelegationRepository.class), mock(EvidenceService.class));

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
        InvestigationRunService service = new InvestigationRunService(repository, registry,
                mock(CapabilitySnapshotRepository.class), mock(AgentDelegationRepository.class),
                mock(EvidenceService.class));

        InvestigationRun result = service.getOrCreate(incidentId);

        assertThat(result).isSameAs(concurrent);
        verify(repository).insert(any(InvestigationRun.class));
    }

    @Test
    void stalesTheOldRunAndCreatesANewRunWhenCapabilitiesChanged() {
        InvestigationRunRepository repository = mock(InvestigationRunRepository.class);
        CapabilityRegistry registry = registry();
        CapabilitySnapshotRepository snapshots = mock(CapabilitySnapshotRepository.class);
        AgentDelegationRepository delegations = mock(AgentDelegationRepository.class);
        EvidenceService evidenceService = mock(EvidenceService.class);
        UUID incidentId = UUID.randomUUID();
        CapabilitySnapshot oldSnapshot = new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.ACTIVE,
                List.of(new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent", "old",
                        URI.create("local://jvm-agent"), "1.0", "jvm-0.9.0",
                        AgentAvailability.AVAILABLE, false)), Instant.now().minusSeconds(60));
        InvestigationRun active = InvestigationRun.pending(incidentId, oldSnapshot.snapshotId(), Instant.now());
        when(repository.findActiveByIncident(incidentId)).thenReturn(Optional.of(active));
        when(snapshots.find(oldSnapshot.snapshotId())).thenReturn(Optional.of(oldSnapshot));
        when(repository.transition(org.mockito.ArgumentMatchers.eq(active.runId()),
                org.mockito.ArgumentMatchers.eq(active.status()),
                org.mockito.ArgumentMatchers.eq(
                        com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus.STALE),
                org.mockito.ArgumentMatchers.eq(active.version()),
                org.mockito.ArgumentMatchers.eq("CAPABILITY_CHANGED"),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        InvestigationRunService service = new InvestigationRunService(repository, registry, snapshots,
                delegations, evidenceService);

        InvestigationRun result = service.getOrCreate(incidentId);

        assertThat(result.runId()).isNotEqualTo(active.runId());
        assertThat(result.capabilitySnapshotId()).isEqualTo(registry.currentSnapshot().snapshotId());
        verify(evidenceService).markRunStale(active.runId());
        verify(delegations).markRecoverableStale(active.runId());
        verify(repository).insert(result);
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
