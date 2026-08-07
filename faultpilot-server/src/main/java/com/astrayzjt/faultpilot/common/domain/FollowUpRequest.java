package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record FollowUpRequest(
        AgentType agentType,
        String objective,
        List<EvidenceType> missingEvidenceTypes,
        List<UUID> evidenceIds) {

    public FollowUpRequest {
        objective = objective == null ? "" : objective.trim();
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : List.copyOf(missingEvidenceTypes);
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
