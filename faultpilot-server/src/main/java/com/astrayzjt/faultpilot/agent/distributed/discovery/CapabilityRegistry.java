package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.util.EnumMap;
import java.util.Map;

public final class CapabilityRegistry {

    private volatile CapabilitySnapshot snapshot;
    private volatile Map<AgentType, AgentCapability> byType = Map.of();

    public synchronized void initialize(CapabilitySnapshot discovered) {
        if (snapshot != null) {
            throw new IllegalStateException("CapabilityRegistry is immutable after startup initialization");
        }
        EnumMap<AgentType, AgentCapability> indexed = new EnumMap<>(AgentType.class);
        for (AgentCapability capability : discovered.agents()) {
            if (indexed.put(capability.agentType(), capability) != null) {
                throw new IllegalArgumentException("Duplicate Agent type in CapabilitySnapshot: "
                        + capability.agentType());
            }
        }
        this.byType = Map.copyOf(indexed);
        this.snapshot = discovered;
    }

    public CapabilitySnapshot currentSnapshot() {
        CapabilitySnapshot current = snapshot;
        if (current == null) {
            throw new IllegalStateException("Agent capabilities have not been discovered yet");
        }
        return current;
    }

    public AgentCapability requireAvailable(AgentType type) {
        AgentCapability capability = byType.get(type);
        if (capability == null || capability.status() != AgentAvailability.AVAILABLE) {
            throw new IllegalArgumentException("Agent is not available: " + type);
        }
        return capability;
    }

    public Map<AgentType, AgentCapability> availableAgents() {
        return byType.entrySet().stream()
                .filter(entry -> entry.getValue().status() == AgentAvailability.AVAILABLE)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
