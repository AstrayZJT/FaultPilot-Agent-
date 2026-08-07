package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record DiagnosisCritique(
        UUID critiqueId,
        UUID proposalId,
        CriticVerdict verdict,
        List<CritiqueIssue> issues,
        String summary) {

    public DiagnosisCritique {
        verdict = verdict == null ? CriticVerdict.REJECT : verdict;
        issues = issues == null ? List.of() : List.copyOf(issues);
        summary = summary == null ? "" : summary;
    }
}
