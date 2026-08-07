package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record HypothesisAssessment(
        CauseCode causeCode,
        AssessmentLevel assessment,
        List<UUID> supportingEvidenceIds,
        List<UUID> counterEvidenceIds,
        String summary) {

    public HypothesisAssessment {
        causeCode = causeCode == null ? CauseCode.UNKNOWN : causeCode;
        assessment = assessment == null ? AssessmentLevel.INSUFFICIENT : assessment;
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        counterEvidenceIds = counterEvidenceIds == null ? List.of() : List.copyOf(counterEvidenceIds);
        summary = summary == null ? "" : summary;
    }
}
