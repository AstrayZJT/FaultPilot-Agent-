package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record CritiqueIssue(
        CritiqueIssueType type,
        String summary,
        List<UUID> evidenceIds,
        List<EvidenceType> missingEvidenceTypes,
        AgentType suggestedAgent) {

    public CritiqueIssue {
        type = type == null ? CritiqueIssueType.MISSING_HIGH_VALUE_CHECK : type;
        summary = summary == null ? "" : summary;
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : List.copyOf(missingEvidenceTypes);
    }
}
