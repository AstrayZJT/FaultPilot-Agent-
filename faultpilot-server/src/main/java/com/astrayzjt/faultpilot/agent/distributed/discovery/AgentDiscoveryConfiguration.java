package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(AgentDiscoveryProperties.class)
public class AgentDiscoveryConfiguration {

    @Bean
    CapabilityRegistry capabilityRegistry() {
        return new CapabilityRegistry();
    }

    @Bean
    AgentCardClient agentCardClient(ObjectMapper objectMapper) {
        return new JdkAgentCardClient(objectMapper);
    }

    @Bean
    CapabilityDiscoveryService capabilityDiscoveryService(AgentDiscoveryProperties properties,
                                                           List<SpecialistAgent> localAgents,
                                                           AgentCardClient cardClient,
                                                           CapabilitySnapshotRepository repository,
                                                           CapabilityRegistry registry) {
        return new CapabilityDiscoveryService(properties, localAgents, cardClient, repository, registry);
    }
}
