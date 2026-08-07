package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record AgentFinding(
        UUID taskId,
        AgentType agentType,
        FindingStatus status,
        CauseCode causeCode,
        List<UUID> supportingEvidenceIds,
        List<UUID> counterEvidenceIds,
        List<EvidenceType> completedChecks,
        List<EvidenceType> missingChecks,
        AgentType suggestedAgent,
        String summary,
        List<HypothesisAssessment> hypotheses,
        String handoffReason,
        int stepsUsed) {

    public AgentFinding {
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        counterEvidenceIds = counterEvidenceIds == null ? List.of() : List.copyOf(counterEvidenceIds);
        completedChecks = completedChecks == null ? List.of() : List.copyOf(completedChecks);
        missingChecks = missingChecks == null ? List.of() : List.copyOf(missingChecks);
        summary = summary == null ? "" : summary;
        handoffReason = handoffReason == null ? "" : handoffReason;
        hypotheses = hypotheses == null || hypotheses.isEmpty()
                ? List.of(new HypothesisAssessment(causeCode == null ? CauseCode.UNKNOWN : causeCode,
                assessment(status, causeCode), supportingEvidenceIds, counterEvidenceIds, summary))
                : List.copyOf(hypotheses);
        stepsUsed = Math.max(0, stepsUsed);
    }

    public AgentFinding(UUID taskId, AgentType agentType, FindingStatus status, CauseCode causeCode,
                        List<UUID> supportingEvidenceIds, List<UUID> counterEvidenceIds,
                        List<EvidenceType> completedChecks, List<EvidenceType> missingChecks,
                        AgentType suggestedAgent, String summary) {
        this(taskId, agentType, status, causeCode, supportingEvidenceIds, counterEvidenceIds, completedChecks,
                missingChecks, suggestedAgent, summary, List.of(), "", 0);
    }

    private static AssessmentLevel assessment(FindingStatus status, CauseCode causeCode) {
        if (causeCode == null || causeCode == CauseCode.UNKNOWN) {
            return AssessmentLevel.INSUFFICIENT;
        }
        return status == FindingStatus.SUCCEEDED ? AssessmentLevel.SUPPORTED : AssessmentLevel.INSUFFICIENT;
    }
}
