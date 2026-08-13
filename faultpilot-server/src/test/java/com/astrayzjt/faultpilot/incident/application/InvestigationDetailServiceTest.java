package com.astrayzjt.faultpilot.incident.application;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisCritiqueRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisProposalRepository;
import com.astrayzjt.faultpilot.diagnosis.EvidenceGateRepository;
import com.astrayzjt.faultpilot.incident.api.InvestigationDetail;
import com.astrayzjt.faultpilot.orchestration.OrchestrationProperties;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentStepRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvestigationDetailServiceTest {

    @Test
    void exposesLatestLoopRunAndEvidenceOnlyDelegationSummary() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID delegationId = UUID.randomUUID();
        Instant now = Instant.now();
        InvestigationRun run = new InvestigationRun(runId, incidentId, UUID.randomUUID(),
                InvestigationRunStatus.RUNNING, BaselineStatus.COMPLETED, null,
                now.minusSeconds(10), null, 2);
        String objective = "Inspect blocked JVM workers";
        EvidenceReferenceArtifact artifact = new EvidenceReferenceArtifact(
                EvidenceReferenceArtifact.SCHEMA_VERSION, delegationId, "jvm-agent", "jvm-1.0.0",
                DelegationStatus.COMPLETED, List.of(evidenceId), 2);
        AgentDelegation delegation = new AgentDelegation(delegationId, runId, incidentId, 1,
                "jvm-agent", AgentType.JVM_AGENT, "jvm-1.0.0", objective,
                DelegationIdentity.objectiveHash(objective),
                DelegationIdentity.idempotencyKey(runId, 1, "jvm-agent", objective), "remote-1",
                DelegationStatus.COMPLETED, artifact, now.minusSeconds(8), now.minusSeconds(2),
                null, null, 1);
        InvestigationRunRepository runs = mock(InvestigationRunRepository.class);
        AgentDelegationRepository delegations = mock(AgentDelegationRepository.class);
        when(runs.findLatestByIncident(incidentId)).thenReturn(Optional.of(run));
        when(delegations.findByRun(runId)).thenReturn(List.of(delegation));
        AgentTaskRepository tasks = mock(AgentTaskRepository.class);
        AgentStepRepository steps = mock(AgentStepRepository.class);
        DiagnosisProposalRepository proposals = mock(DiagnosisProposalRepository.class);
        DiagnosisCritiqueRepository critiques = mock(DiagnosisCritiqueRepository.class);
        EvidenceGateRepository gates = mock(EvidenceGateRepository.class);
        when(tasks.findTaskSummariesByIncident(incidentId)).thenReturn(List.of());
        when(steps.findStepSummariesByIncident(incidentId)).thenReturn(List.of());
        when(proposals.findByIncident(incidentId)).thenReturn(List.of());
        when(gates.findLatestByIncident(incidentId)).thenReturn(Optional.empty());
        OrchestrationProperties properties = new OrchestrationProperties();
        InvestigationDetailService service = new InvestigationDetailService(tasks, steps, proposals, critiques,
                gates, runs, delegations, properties);

        InvestigationDetail detail = service.find(incidentId);

        assertThat(detail.orchestrationMode()).isEqualTo("LOOP");
        assertThat(detail.run().runId()).isEqualTo(runId);
        assertThat(detail.delegations()).singleElement().satisfies(item -> {
            assertThat(item.agentType()).isEqualTo(AgentType.JVM_AGENT);
            assertThat(item.status()).isEqualTo(DelegationStatus.COMPLETED);
            assertThat(item.evidenceIds()).containsExactly(evidenceId);
        });
    }
}
