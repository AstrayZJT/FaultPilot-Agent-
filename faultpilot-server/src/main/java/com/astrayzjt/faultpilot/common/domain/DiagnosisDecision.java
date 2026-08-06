package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record DiagnosisDecision(
        DiagnosisStatus status,
        CauseCode primaryCause,
        List<CauseCode> contributingFactors,
        List<UUID> supportingEvidenceIds,
        List<UUID> counterEvidenceIds,
        List<EvidenceType> missingEvidenceTypes,
        String summary) {
}

