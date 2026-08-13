package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.FindingStatus;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalSpecialistTransportTest {

    @Test
    void returnsOnlyEvidenceReferencesFromTheLocalSpecialist() {
        UUID runId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AgentDelegation delegation = delegation(runId, incidentId);
        Evidence produced = evidence(runId, incidentId, delegation.delegationId(), evidenceId);
        SpecialistAgent agent = mock(SpecialistAgent.class);
        when(agent.type()).thenReturn(AgentType.JVM_AGENT);
        when(agent.investigate(any(), any(), any())).thenReturn(new AgentFinding(delegation.delegationId(),
                AgentType.JVM_AGENT, FindingStatus.SUCCEEDED, CauseCode.JVM_CPU_HOTSPOT,
                List.of(evidenceId), List.of(), List.of(EvidenceType.PROCESS_CPU_HIGH), List.of(), null,
                "CPU evidence is sufficient", List.of(), "", 2));
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        when(tasks.findFinding(delegation.delegationId())).thenReturn(java.util.Optional.empty());
        EvidenceService evidenceService = mock(EvidenceService.class);
        when(evidenceService.findActiveByRunAndTask(runId, delegation.delegationId())).thenReturn(List.of(produced));
        LocalSpecialistTransport transport = new LocalSpecialistTransport(List.of(agent), tasks, evidenceService);

        var artifact = transport.execute(delegation, incident(incidentId), List.of(), Instant.now().plusSeconds(30));

        assertThat(artifact.executionStatus()).isEqualTo(DelegationStatus.COMPLETED);
        assertThat(artifact.evidenceIds()).containsExactly(evidenceId);
        assertThat(artifact.stepsUsed()).isEqualTo(2);
        verify(tasks).insert(any(AgentTask.class));
        verify(tasks).markRunning(any(AgentTask.class));
    }

    @Test
    void reusesPersistedFindingForAnIdempotentLocalTask() {
        UUID runId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        AgentDelegation delegation = delegation(runId, incidentId);
        SpecialistAgent agent = mock(SpecialistAgent.class);
        when(agent.type()).thenReturn(AgentType.JVM_AGENT);
        AgentFinding persisted = new AgentFinding(delegation.delegationId(), AgentType.JVM_AGENT,
                FindingStatus.SUCCEEDED, CauseCode.JVM_CPU_HOTSPOT, List.of(evidenceId), List.of(),
                List.of(EvidenceType.PROCESS_CPU_HIGH), List.of(), null, "persisted", List.of(), "", 1);
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        when(tasks.findFinding(delegation.delegationId())).thenReturn(java.util.Optional.of(persisted));
        EvidenceService evidenceService = mock(EvidenceService.class);
        when(evidenceService.findActiveByRunAndTask(runId, delegation.delegationId()))
                .thenReturn(List.of(evidence(runId, incidentId, delegation.delegationId(), evidenceId)));
        LocalSpecialistTransport transport = new LocalSpecialistTransport(List.of(agent), tasks, evidenceService);

        var artifact = transport.execute(delegation, incident(incidentId), List.of(), Instant.now().plusSeconds(30));

        assertThat(artifact.executionStatus()).isEqualTo(DelegationStatus.COMPLETED);
        assertThat(artifact.evidenceIds()).containsExactly(evidenceId);
        org.mockito.Mockito.verify(agent, org.mockito.Mockito.never()).investigate(any(), any(), any());
    }

    private AgentDelegation delegation(UUID runId, UUID incidentId) {
        String objective = "Inspect JVM CPU evidence";
        return new AgentDelegation(UUID.randomUUID(), runId, incidentId, 1, "jvm-agent",
                AgentType.JVM_AGENT, "jvm-1.0.0", objective, DelegationIdentity.objectiveHash(objective),
                DelegationIdentity.idempotencyKey(runId, 1, "jvm-agent", objective), null,
                DelegationStatus.RUNNING, null, Instant.now(), null, null, null, 1);
    }

    private IncidentSnapshot incident(UUID incidentId) {
        Instant now = Instant.now();
        return new IncidentSnapshot(incidentId, "order-service", "CPU is high", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
    }

    private Evidence evidence(UUID runId, UUID incidentId, UUID taskId, UUID evidenceId) {
        Instant now = Instant.now();
        return new Evidence(evidenceId, incidentId, taskId, runId, "jvm-agent",
                "query_prometheus_process_cpu", "call-1", "jvm-1.0.0", EvidenceStatus.ACTIVE,
                EvidenceType.PROCESS_CPU_HIGH, "prometheus:order-service:process_cpu_usage", "order-service",
                now.minusSeconds(60), now, "CPU high", null, "hash", java.util.Map.of(), now);
    }
}
