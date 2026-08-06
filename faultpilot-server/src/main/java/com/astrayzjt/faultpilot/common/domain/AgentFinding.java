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
        String summary) {
}

