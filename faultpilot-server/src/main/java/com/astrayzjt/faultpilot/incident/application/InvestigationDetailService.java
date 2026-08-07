package com.astrayzjt.faultpilot.incident.application;

import com.astrayzjt.faultpilot.diagnosis.DiagnosisCritiqueRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisProposalRepository;
import com.astrayzjt.faultpilot.diagnosis.EvidenceGateRepository;
import com.astrayzjt.faultpilot.incident.api.InvestigationDetail;
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

    public InvestigationDetailService(AgentTaskRepository taskRepository, AgentStepRepository stepRepository,
                                      DiagnosisProposalRepository proposalRepository,
                                      DiagnosisCritiqueRepository critiqueRepository,
                                      EvidenceGateRepository gateRepository) {
        this.taskRepository = taskRepository;
        this.stepRepository = stepRepository;
        this.proposalRepository = proposalRepository;
        this.critiqueRepository = critiqueRepository;
        this.gateRepository = gateRepository;
    }

    public InvestigationDetail find(UUID incidentId) {
        List<com.astrayzjt.faultpilot.common.domain.DiagnosisProposal> proposals = proposalRepository.findByIncident(incidentId);
        List<InvestigationDetail.CritiqueSummary> critiques = proposals.stream()
                .flatMap(proposal -> critiqueRepository.findByProposal(proposal.proposalId()).stream()
                        .map(critique -> new InvestigationDetail.CritiqueSummary(proposal.proposalId(), critique)))
                .toList();
        return new InvestigationDetail(incidentId, taskRepository.findTaskSummariesByIncident(incidentId),
                stepRepository.findStepSummariesByIncident(incidentId), proposals, critiques,
                gateRepository.findLatestByIncident(incidentId).orElse(null));
    }
}
