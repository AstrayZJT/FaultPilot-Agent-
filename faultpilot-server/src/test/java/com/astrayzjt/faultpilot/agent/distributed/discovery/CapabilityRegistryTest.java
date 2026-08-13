package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityRegistryTest {

    @Test
    void exposesOnlyAvailableAgentsAndCannotBeReinitialized() {
        CapabilityRegistry registry = new CapabilityRegistry();
        CapabilitySnapshot snapshot = new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.DEGRADED,
                List.of(capability(AgentType.JVM_AGENT, AgentAvailability.AVAILABLE),
                        capability(AgentType.DATABASE_AGENT, AgentAvailability.UNAVAILABLE)), Instant.now());

        registry.initialize(snapshot);

        assertThat(registry.currentSnapshot()).isSameAs(snapshot);
        assertThat(registry.availableAgents()).containsOnlyKeys(AgentType.JVM_AGENT);
        assertThat(registry.requireAvailable(AgentType.JVM_AGENT).agentId()).isEqualTo("jvm-agent");
        assertThatThrownBy(() -> registry.requireAvailable(AgentType.DATABASE_AGENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.initialize(snapshot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable");
    }

    private AgentCapability capability(AgentType type, AgentAvailability availability) {
        String id = type.name().toLowerCase().replace('_', '-');
        return new AgentCapability(id, type, id, "Capability summary for " + id,
                URI.create("local://" + id), "1.0", "local-1.0.0", availability, false);
    }
}
