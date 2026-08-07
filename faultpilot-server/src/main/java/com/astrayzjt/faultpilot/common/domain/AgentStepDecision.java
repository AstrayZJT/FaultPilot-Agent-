package com.astrayzjt.faultpilot.common.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record AgentStepDecision(
        AgentStepAction action,
        String toolName,
        JsonNode arguments,
        List<UUID> evidenceIds,
        AgentType suggestedAgent,
        String decisionSummary) {

    public AgentStepDecision {
        action = action == null ? AgentStepAction.COMPLETE : action;
        toolName = toolName == null ? "" : toolName.trim();
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        decisionSummary = decisionSummary == null ? "" : decisionSummary.substring(0, Math.min(300, decisionSummary.length()));
    }
}
