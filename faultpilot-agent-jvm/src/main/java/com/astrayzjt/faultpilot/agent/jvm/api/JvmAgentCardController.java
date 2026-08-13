package com.astrayzjt.faultpilot.agent.jvm.api;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.AgentCard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JvmAgentCardController {

    private final JvmAgentProperties properties;

    public JvmAgentCardController(JvmAgentProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/agent-card.json")
    public AgentCard card() {
        return new AgentCard(properties.getAgentId(), "JVM_AGENT", properties.getName(),
                "Investigates JVM CPU hotspots and worker-pool exhaustion using Prometheus and Arthas Skills",
                properties.getPublicUrl(), properties.getProtocolVersion(), properties.getCapabilityVersion(),
                "AVAILABLE", true);
    }
}
