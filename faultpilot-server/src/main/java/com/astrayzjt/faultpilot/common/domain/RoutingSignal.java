package com.astrayzjt.faultpilot.common.domain;

import java.util.List;
import java.util.UUID;

public record RoutingSignal(
        AgentType agentType,
        int score,
        List<UUID> evidenceIds,
        String reasonCode) {

    public RoutingSignal {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        reasonCode = reasonCode == null ? "" : reasonCode;
    }
}
