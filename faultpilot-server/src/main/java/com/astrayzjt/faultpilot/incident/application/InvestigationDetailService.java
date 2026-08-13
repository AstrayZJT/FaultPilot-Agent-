package com.astrayzjt.faultpilot.incident.application;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisCritiqueRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisProposalRepository;
import com.astrayzjt.faultpilot.diagnosis.EvidenceGateRepository;
import com.astrayzjt.faultpilot.incident.api.InvestigationDetail;
import com.astrayzjt.faultpilot.orchestration.OrchestrationProperties;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentStepRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentTaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class InvestigationDetailService {

    private final AgentTaskRepository taskRepository;
    private final AgentStepRepository stepRepository;
    private final DiagnosisProposalRepository proposalRepository;
    private final DiagnosisCritiqueRepository critiqueRepository;
    private final EvidenceGateRepository gateRepository;
    private final InvestigationRunRepository runRepository;
    private final AgentDelegationRepository delegationRepository;
    private final OrchestrationProperties orchestrationProperties;

    public InvestigationDetailService(AgentTaskRepository taskRepository, AgentStepRepository stepRepository,
                                      DiagnosisProposalRepository proposalRepository,
                                      DiagnosisCritiqueRepository critiqueRepository,
                                      EvidenceGateRepository gateRepository,
                                      InvestigationRunRepository runRepository,
                                      AgentDelegationRepository delegationRepository,
                                      OrchestrationProperties orchestrationProperties) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.proposalRepository = proposalRepository;
        this.critiqueRepository = critiqueRepository;
        this.gateRepository = gateRepository;
        this.runRepository = runRepository;
        this.delegationRepository = delegationRepository;
        this.orchestrationProperties = orchestrationProperties;
    }

    public InvestigationDetail find(UUID incidentId) {
        List<com.astrayzjt.faultpilot.common.domain.DiagnosisProposal> proposals = proposalRepository.findByIncident(incidentId);
        List<InvestigationDetail.CritiqueSummary> critiques = proposals.stream()
                .flatMap(proposal -> critiqueRepository.findByProposal(proposal.proposalId()).stream()
                        .map(critique -> new InvestigationDetail.CritiqueSummary(proposal.proposalId(), critique)))
                .toList();
        InvestigationRun run = runRepository.findLatestByIncident(incidentId).orElse(null);
        List<InvestigationDetail.DelegationSummary> delegations = run == null ? List.of()
                : delegationRepository.findByRun(run.runId()).stream().map(this::delegationSummary).toList();
        return new InvestigationDetail(incidentId, orchestrationProperties.getMode().name(), runSummary(run), delegations,
                taskRepository.findTaskSummariesByIncident(incidentId),
                stepRepository.findStepSummariesByIncident(incidentId), proposals, critiques,
                gateRepository.findLatestByIncident(incidentId).orElse(null));
    }

    private InvestigationDetail.RunSummary runSummary(InvestigationRun run) {
        return run == null ? null : new InvestigationDetail.RunSummary(run.runId(), run.status(),
                run.baselineStatus(), run.restartReason(), run.startedAt(), run.completedAt());
    }

    private InvestigationDetail.DelegationSummary delegationSummary(AgentDelegation delegation) {
        List<UUID> evidenceIds = delegation.artifact() == null ? List.of()
                : delegation.artifact().evidenceIds();
        return new InvestigationDetail.DelegationSummary(delegation.delegationId(), delegation.round(),
                delegation.agentId(), delegation.agentType(), delegation.capabilityVersion(), delegation.objective(),
                delegation.remoteTaskId(), delegation.status(), evidenceIds, delegation.startedAt(),
                delegation.completedAt(), delegation.errorCode(), delegation.errorMessage());
    }
}
