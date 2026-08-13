package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.AgentCard;
import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class CapabilityDiscoveryServiceTest {

    @Test
    void discoversLocalSpecialistsOnceWithConfiguredVersions() {
        AgentDiscoveryProperties properties = new AgentDiscoveryProperties();
        EnumMap<AgentType, String> versions = new EnumMap<>(AgentType.class);
        versions.put(AgentType.JVM_AGENT, "jvm-local-2.0.0");
        properties.setLocalVersions(versions);
        SpecialistAgent jvm = mock(SpecialistAgent.class);
        org.mockito.Mockito.when(jvm.type()).thenReturn(AgentType.JVM_AGENT);
        CapabilityDiscoveryService service = service(properties, List.of(jvm), (uri, timeout) -> null);

        var snapshot = service.discover();

        assertThat(snapshot.status()).isEqualTo(CapabilitySnapshotStatus.ACTIVE);
        assertThat(snapshot.agents()).singleElement().satisfies(agent -> {
            assertThat(agent.agentId()).isEqualTo("jvm-agent");
            assertThat(agent.capabilityVersion()).isEqualTo("jvm-local-2.0.0");
            assertThat(agent.url()).isEqualTo(URI.create("local://jvm-agent"));
        });
    }

    @Test
    void optionalRemoteAgentFailureCreatesDegradedSnapshot() {
        AgentDiscoveryProperties properties = remoteProperties(false);
        CapabilityDiscoveryService service = service(properties, List.of(), (uri, timeout) -> {
            throw new IllegalStateException("offline");
        });

        var snapshot = service.discover();

        assertThat(snapshot.status()).isEqualTo(CapabilitySnapshotStatus.DEGRADED);
        assertThat(snapshot.agents()).singleElement()
                .extracting("status").isEqualTo(AgentAvailability.UNAVAILABLE);
    }

    @Test
    void requiredRemoteAgentFailureAndIdentityMismatchFailDiscovery() {
        AgentDiscoveryProperties required = remoteProperties(true);
        assertThatThrownBy(() -> service(required, List.of(), (uri, timeout) -> {
            throw new IllegalStateException("offline");
        }).discover()).isInstanceOf(IllegalStateException.class).hasMessageContaining("Required Agent Card");

        AgentCard wrongIdentity = new AgentCard("database-agent", AgentType.DATABASE_AGENT, "DB Agent",
                "Investigates databases", URI.create("http://database-agent:8092/a2a"), "1.0", "db-1.0.0",
                AgentAvailability.AVAILABLE, true);
        assertThatThrownBy(() -> service(required, List.of(), (uri, timeout) -> wrongIdentity).discover())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Required Agent Card");
    }

    private CapabilityDiscoveryService service(AgentDiscoveryProperties properties,
                                                List<SpecialistAgent> agents,
                                                AgentCardClient client) {
        return new CapabilityDiscoveryService(properties, agents, client,
                mock(CapabilitySnapshotRepository.class), new CapabilityRegistry());
    }

    private AgentDiscoveryProperties remoteProperties(boolean required) {
        AgentDiscoveryProperties properties = new AgentDiscoveryProperties();
        properties.setTransport(AgentDiscoveryProperties.Transport.A2A);
        AgentDiscoveryProperties.RemoteAgentProperties endpoint = new AgentDiscoveryProperties.RemoteAgentProperties();
        endpoint.setAgentType(AgentType.JVM_AGENT);
        endpoint.setCardUrl("http://jvm-agent:8091/.well-known/agent-card.json");
        endpoint.setRequired(required);
        properties.setEndpoints(Map.of("jvm-agent", endpoint));
        return properties;
    }
}
