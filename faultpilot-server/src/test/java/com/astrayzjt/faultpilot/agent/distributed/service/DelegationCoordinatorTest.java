package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.agent.distributed.transport.SpecialistTransport;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelegationCoordinatorTest {

    @Test
    void reusesTerminalDelegationWithoutExecutingTransportAgain() {
        Fixture fixture = fixture();
        UUID evidenceId = fixture.evidence.evidenceId();
        AgentDelegation completed = completed(fixture, evidenceId);
        when(fixture.repository.insert(any())).thenReturn(false);
        when(fixture.repository.findByIdempotencyKey(any())).thenReturn(Optional.of(completed));

        AgentDelegation result = fixture.coordinator.execute(fixture.run, 1, AgentType.JVM_AGENT,
                "Inspect JVM CPU evidence", fixture.incident, Instant.now().plusSeconds(30));

        assertThat(result).isSameAs(completed);
        verify(fixture.transport, never()).execute(any(), any(), any(), any());
    }

    @Test
    void rejectsArtifactThatReferencesEvidenceOutsideTheRun() {
        Fixture fixture = fixture();
        AtomicReference<AgentDelegation> state = new AtomicReference<>();
        when(fixture.repository.insert(any())).thenAnswer(invocation -> {
            state.set(invocation.getArgument(0));
            return true;
        });
        when(fixture.repository.find(any())).thenAnswer(invocation -> {
            AgentDelegation current = state.get();
            if (current.status() == DelegationStatus.PENDING) {
                current = running(current);
                state.set(current);
            }
            return Optional.of(current);
        });
        when(fixture.repository.transition(any(), any(), any(), any(Long.class), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    AgentDelegation current = state.get();
                    DelegationStatus target = invocation.getArgument(2);
                    EvidenceReferenceArtifact artifact = invocation.getArgument(5);
                    state.set(target.terminal() ? terminal(current, target, artifact) : running(current));
                    return true;
                });
        when(fixture.evidenceService.findActiveByRun(fixture.run.runId())).thenReturn(List.of(fixture.evidence));
        UUID foreignId = UUID.randomUUID();
        when(fixture.transport.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            AgentDelegation delegation = invocation.getArgument(0);
            return new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                    delegation.delegationId(), delegation.agentId(), delegation.capabilityVersion(),
                    DelegationStatus.COMPLETED, List.of(foreignId), 1);
        });

        AgentDelegation result = fixture.coordinator.execute(fixture.run, 1, AgentType.JVM_AGENT,
                "Inspect JVM CPU evidence", fixture.incident, Instant.now().plusSeconds(30));

        assertThat(result.status()).isEqualTo(DelegationStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo("IllegalArgumentException");
    }

    private Fixture fixture() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();
        CapabilityRegistry registry = new CapabilityRegistry();
        AgentCapability capability = new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent",
                "Investigates JVM faults", URI.create("local://jvm-agent"), "1.0", "jvm-1.0.0",
                AgentAvailability.AVAILABLE, false);
        CapabilitySnapshot snapshot = new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.ACTIVE,
                List.of(capability), now);
        registry.initialize(snapshot);
        InvestigationRun run = new InvestigationRun(runId, incidentId, snapshot.snapshotId(),
                InvestigationRunStatus.RUNNING, BaselineStatus.COMPLETED, null, now.minusSeconds(3), null, 1);
        IncidentSnapshot incident = new IncidentSnapshot(incidentId, "order-service", "CPU is high", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        Evidence evidence = new Evidence(UUID.randomUUID(), incidentId, null, runId, "jvm-agent",
                "query_prometheus_process_cpu", "baseline:cpu", "jvm-1.0.0", EvidenceStatus.ACTIVE,
                EvidenceType.PROCESS_CPU_HIGH, "prometheus:order-service:process_cpu_usage", "order-service",
                now.minusSeconds(60), now, "CPU high", null, "hash", java.util.Map.of(), now);
        AgentDelegationRepository repository = mock(AgentDelegationRepository.class);
        SpecialistTransport transport = mock(SpecialistTransport.class);
        EvidenceService evidenceService = mock(EvidenceService.class);
        DelegationCoordinator coordinator = new DelegationCoordinator(repository, registry, transport, evidenceService);
        return new Fixture(coordinator, repository, transport, evidenceService, run, incident, evidence);
    }

    private AgentDelegation completed(Fixture fixture, UUID evidenceId) {
        AgentDelegation pending = new DelegationFactory().create(fixture.run, 1,
                fixture.coordinatorCapabilities(), "Inspect JVM CPU evidence");
        EvidenceReferenceArtifact artifact = new EvidenceReferenceArtifact(EvidenceReferenceArtifact.SCHEMA_VERSION,
                pending.delegationId(), pending.agentId(), pending.capabilityVersion(), DelegationStatus.COMPLETED,
                List.of(evidenceId), 1);
        return terminal(pending, DelegationStatus.COMPLETED, artifact);
    }

    private AgentDelegation running(AgentDelegation value) {
        return new AgentDelegation(value.delegationId(), value.runId(), value.incidentId(), value.round(),
                value.agentId(), value.agentType(), value.capabilityVersion(), value.objective(), value.objectiveHash(),
                value.idempotencyKey(), null, DelegationStatus.RUNNING, null, Instant.now(), null,
                null, null, value.version() + 1);
    }

    private AgentDelegation terminal(AgentDelegation value, DelegationStatus status,
                                     EvidenceReferenceArtifact artifact) {
        return new AgentDelegation(value.delegationId(), value.runId(), value.incidentId(), value.round(),
                value.agentId(), value.agentType(), value.capabilityVersion(), value.objective(), value.objectiveHash(),
                value.idempotencyKey(), null, status, artifact, value.startedAt() == null ? Instant.now() : value.startedAt(),
                Instant.now(), status == DelegationStatus.FAILED ? "IllegalArgumentException" : null,
                status == DelegationStatus.FAILED ? "foreign evidence" : null, value.version() + 1);
    }

    private record Fixture(DelegationCoordinator coordinator, AgentDelegationRepository repository,
                           SpecialistTransport transport, EvidenceService evidenceService, InvestigationRun run,
                           IncidentSnapshot incident, Evidence evidence) {
        private AgentCapability coordinatorCapabilities() {
            return new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent", "Investigates JVM faults",
                    URI.create("local://jvm-agent"), "1.0", "jvm-1.0.0", AgentAvailability.AVAILABLE, false);
        }
    }
}
