package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.util.List;
import java.util.UUID;

public record AgentTaskDraft(AgentType agentType, String objective, List<UUID> evidenceIds) {
    public AgentTaskDraft {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
