package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentStepAction;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.astrayzjt.faultpilot.common.domain.EvidenceGateResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Auditable investigation summaries. This deliberately excludes prompts, model chain-of-thought, and tool arguments.
 */
public record InvestigationDetail(
        UUID incidentId,
        List<AgentTaskSummary> tasks,
        List<AgentStepSummary> steps,
        List<DiagnosisProposal> proposals,
        List<CritiqueSummary> critiques,
        EvidenceGateResult latestEvidenceGate) {

    public InvestigationDetail {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        steps = steps == null ? List.of() : List.copyOf(steps);
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        critiques = critiques == null ? List.of() : List.copyOf(critiques);
    }

    public record AgentTaskSummary(UUID taskId, AgentType agentType, String objective, String status,
                                   int investigationRound, int maxSteps, Instant startedAt, Instant completedAt,
                                   AgentFinding finding, String errorCode) {
    }

    public record AgentStepSummary(UUID stepId, UUID taskId, AgentType agentType, int investigationRound,
                                   int stepIndex, AgentStepAction action, String toolName, String decisionSummary,
                                   String status, UUID evidenceId, Instant startedAt, Instant completedAt) {
    }

    public record CritiqueSummary(UUID proposalId, DiagnosisCritique critique) {
    }
}
