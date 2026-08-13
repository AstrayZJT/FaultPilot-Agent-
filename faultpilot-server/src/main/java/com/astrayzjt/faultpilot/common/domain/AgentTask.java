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
        Instant completedAt,
        UUID runId,
        String agentId,
        String capabilityVersion) {

    public AgentTask(UUID taskId, UUID incidentId, String taskKey, AgentType agentType, String objective,
                     int maxSteps, int investigationRound, AgentTaskStatus status,
                     Instant startedAt, Instant completedAt) {
        this(taskId, incidentId, taskKey, agentType, objective, maxSteps, investigationRound, status,
                startedAt, completedAt, null, null, null);
    }
}
