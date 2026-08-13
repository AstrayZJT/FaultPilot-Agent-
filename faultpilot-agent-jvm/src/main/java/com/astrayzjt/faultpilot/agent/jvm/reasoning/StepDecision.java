package com.astrayzjt.faultpilot.agent.jvm.reasoning;

import java.util.List;
import java.util.UUID;

public record StepDecision(StepAction action, String toolName, List<UUID> evidenceIds, String rationale) {
    public StepDecision {
        action = action == null ? StepAction.INSUFFICIENT : action;
        toolName = toolName == null ? "" : toolName.trim();
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        rationale = rationale == null ? "" : rationale.substring(0, Math.min(300, rationale.length()));
    }
}
