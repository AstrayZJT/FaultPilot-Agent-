package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record EvidenceGateResult(
        DiagnosisStatus status,
        CauseCode primaryCause,
        List<UUID> acceptedSupportingEvidenceIds,
        List<UUID> acceptedCounterEvidenceIds,
        List<EvidenceType> missingEvidenceTypes,
        List<String> rejectionReasons,
        String summary) {

    public EvidenceGateResult {
        status = status == null ? DiagnosisStatus.INCONCLUSIVE : status;
        primaryCause = primaryCause == null ? CauseCode.UNKNOWN : primaryCause;
        acceptedSupportingEvidenceIds = acceptedSupportingEvidenceIds == null ? List.of() : List.copyOf(acceptedSupportingEvidenceIds);
        acceptedCounterEvidenceIds = acceptedCounterEvidenceIds == null ? List.of() : List.copyOf(acceptedCounterEvidenceIds);
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : List.copyOf(missingEvidenceTypes);
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        summary = summary == null ? "" : summary;
    }
}
