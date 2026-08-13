package com.astrayzjt.faultpilot.agent.jvm.protocol;

import java.net.URI;

public record AgentCard(
        String agentId,
        String agentType,
        String name,
        String description,
        URI url,
        String protocolVersion,
        String capabilityVersion,
        String status,
        boolean supportsCancel) {
}
