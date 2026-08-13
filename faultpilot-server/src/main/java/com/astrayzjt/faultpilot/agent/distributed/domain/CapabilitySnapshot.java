package com.astrayzjt.faultpilot.agent.distributed.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record CapabilitySnapshot(
        UUID snapshotId,
        CapabilitySnapshotStatus status,
        List<AgentCapability> agents,
        Instant discoveredAt) {

    public CapabilitySnapshot {
        if (snapshotId == null || status == null || discoveredAt == null) {
            throw new IllegalArgumentException("Capability snapshot identity, status and discoveredAt are required");
        }
        agents = agents == null ? List.of() : agents.stream()
                .sorted(Comparator.comparing(AgentCapability::agentId))
                .toList();
        if (agents.isEmpty()) {
            throw new IllegalArgumentException("Capability snapshot must contain at least one Agent");
        }
        if (new HashSet<>(agents.stream().map(AgentCapability::agentId).toList()).size() != agents.size()) {
            throw new IllegalArgumentException("Capability snapshot contains duplicate Agent IDs");
        }
    }

    public boolean sameCapabilities(CapabilitySnapshot other) {
        if (other == null || agents.size() != other.agents.size()) {
            return false;
        }
        for (int index = 0; index < agents.size(); index++) {
            AgentCapability left = agents.get(index);
            AgentCapability right = other.agents.get(index);
            if (!left.agentId().equals(right.agentId())
                    || left.agentType() != right.agentType()
                    || left.status() != right.status()
                    || !left.capabilityVersion().equals(right.capabilityVersion())) {
                return false;
            }
        }
        return true;
    }
}
