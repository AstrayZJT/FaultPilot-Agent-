package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.util.List;
import java.util.UUID;

/**
 * The Main Agent's bounded diagnosis proposal. SummaryAgent may format it, but
 * it is not allowed to change its causal fields or evidence references.
 */
public record DiagnosisDraft(
        DiagnosisStatus status,
        CauseCode primaryCause,
        List<CauseCode> contributingFactors,
        List<UUID> supportingEvidenceIds,
        List<UUID> counterEvidenceIds,
        List<EvidenceType> missingEvidenceTypes,
        String summary) {

    public DiagnosisDraft {
        status = status == null ? DiagnosisStatus.INCONCLUSIVE : status;
        primaryCause = primaryCause == null ? CauseCode.UNKNOWN : primaryCause;
        contributingFactors = contributingFactors == null ? List.of() : List.copyOf(contributingFactors);
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : supportingEvidenceIds.stream().distinct().toList();
        counterEvidenceIds = counterEvidenceIds == null ? List.of() : counterEvidenceIds.stream().distinct().toList();
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : missingEvidenceTypes.stream().distinct().toList();
        summary = summary == null ? "" : summary.trim();
    }

    public DiagnosisDecision toDecision(String finalSummary) {
        return new DiagnosisDecision(status, primaryCause, contributingFactors, supportingEvidenceIds,
                counterEvidenceIds, missingEvidenceTypes,
                finalSummary == null || finalSummary.isBlank() ? summary : finalSummary.trim());
    }
}
