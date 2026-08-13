package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentDiscoveryPropertiesTest {

    @Test
    void bindsLocalCapabilityVersionsAndRemoteCards() {
        new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class)
                .withPropertyValues(
                        "faultpilot.agents.transport=A2A",
                        "faultpilot.agents.local-versions.JVM_AGENT=jvm-2.0.0",
                        "faultpilot.agents.endpoints.jvm-agent.agent-type=JVM_AGENT",
                        "faultpilot.agents.endpoints.jvm-agent.card-url=http://localhost:8091/.well-known/agent-card.json",
                        "faultpilot.agents.endpoints.jvm-agent.required=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AgentDiscoveryProperties properties = context.getBean(AgentDiscoveryProperties.class);
                    assertThat(properties.getTransport()).isEqualTo(AgentDiscoveryProperties.Transport.A2A);
                    assertThat(properties.getLocalVersions()).containsEntry(AgentType.JVM_AGENT, "jvm-2.0.0");
                    assertThat(properties.getEndpoints().get("jvm-agent").isRequired()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentDiscoveryProperties.class)
    static class TestConfiguration {
    }
}
