package com.astrayzjt.faultpilot.tool.registry;

import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.time.Instant;
import java.util.UUID;

public record ToolExecutionContext(
        UUID incidentId,
        UUID taskId,
        AgentType agentType,
        String serviceName,
        Instant deadline) {
    public void throwIfExpired() {
        if (Instant.now().isAfter(deadline)) {
            throw new IllegalStateException("Tool deadline exceeded");
        }
    }
}
