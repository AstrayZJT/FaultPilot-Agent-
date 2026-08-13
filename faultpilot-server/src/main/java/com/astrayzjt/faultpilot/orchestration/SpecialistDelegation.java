package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;

public record SpecialistDelegation(AgentType agentType, String objective) {

    public SpecialistDelegation {
        if (agentType == null) {
            throw new IllegalArgumentException("Specialist delegation requires an Agent type");
        }
        objective = objective == null ? "" : objective.trim();
        if (objective.isBlank()) {
            throw new IllegalArgumentException("Specialist delegation requires an objective");
        }
    }
}
