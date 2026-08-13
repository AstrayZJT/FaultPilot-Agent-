package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.util.List;
import java.util.UUID;

public record MainAgentDecision(
        MainAgentAction action,
        AgentType agentType,
        String objective,
        List<UUID> evidenceIds,
        DiagnosisDraft draft,
        String reason) {

    public MainAgentDecision {
        if (action == null) {
            throw new IllegalArgumentException("Main Agent action is required");
        }
        objective = objective == null ? "" : objective.trim();
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        reason = reason == null ? "" : reason.trim();
        if (action == MainAgentAction.DELEGATE && (agentType == null || objective.isBlank())) {
            throw new IllegalArgumentException("DELEGATE requires an Agent and objective");
        }
    }

    public static MainAgentDecision inconclusive(String reason) {
        return new MainAgentDecision(MainAgentAction.INCONCLUSIVE, null, "", List.of(), null, reason);
    }
}
