package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record DiagnosisProposal(
        UUID proposalId,
        UUID incidentId,
        int investigationRound,
        int revision,
        ProposalStatus status,
        CauseCode primaryCause,
        List<CauseCode> contributingFactors,
        List<UUID> supportingEvidenceIds,
        List<UUID> counterEvidenceIds,
        List<EvidenceType> missingEvidenceTypes,
        List<FollowUpRequest> requestedFollowUps,
        String causalSummary) {

    public DiagnosisProposal {
        status = status == null ? ProposalStatus.INSUFFICIENT : status;
        primaryCause = primaryCause == null ? CauseCode.UNKNOWN : primaryCause;
        contributingFactors = contributingFactors == null ? List.of() : List.copyOf(contributingFactors);
        supportingEvidenceIds = supportingEvidenceIds == null ? List.of() : List.copyOf(supportingEvidenceIds);
        counterEvidenceIds = counterEvidenceIds == null ? List.of() : List.copyOf(counterEvidenceIds);
        missingEvidenceTypes = missingEvidenceTypes == null ? List.of() : List.copyOf(missingEvidenceTypes);
        requestedFollowUps = requestedFollowUps == null ? List.of() : List.copyOf(requestedFollowUps);
        causalSummary = causalSummary == null ? "" : causalSummary;
        investigationRound = Math.max(0, investigationRound);
        revision = Math.max(0, revision);
    }
}
