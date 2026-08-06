package com.astrayzjt.faultpilot.common.domain;

import java.time.Instant;
import java.util.UUID;

public record AgentTask(
        UUID taskId,
        UUID incidentId,
        String taskKey,
        AgentType agentType,
        String objective,
        int maxSteps,
        int investigationRound,
        AgentTaskStatus status,
        Instant startedAt,
        Instant completedAt) {
}

