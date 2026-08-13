package com.astrayzjt.faultpilot.orchestration;

import java.util.List;
import java.util.UUID;

public record MainAgentDecision(
        MainAgentAction action,
        List<SpecialistDelegation> delegations,
        List<UUID> evidenceIds,
        DiagnosisDraft draft,
        String reason) {

    public MainAgentDecision {
        if (action == null) {
            throw new IllegalArgumentException("Main Agent action is required");
        }
        delegations = delegations == null ? List.of() : List.copyOf(delegations);
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        reason = reason == null ? "" : reason.trim();
        if (action == MainAgentAction.DELEGATE && delegations.isEmpty()) {
            throw new IllegalArgumentException("DELEGATE requires at least one specialist delegation");
        }
        if (action != MainAgentAction.DELEGATE && !delegations.isEmpty()) {
            throw new IllegalArgumentException(action + " must not include specialist delegations");
        }
    }

    public static MainAgentDecision inconclusive(String reason) {
        return new MainAgentDecision(MainAgentAction.INCONCLUSIVE, List.of(), List.of(), null, reason);
    }
}
