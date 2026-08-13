package com.astrayzjt.faultpilot.agent.distributed.protocol;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.net.URI;

public record AgentCard(
        String agentId,
        AgentType agentType,
        String name,
        String description,
        URI url,
        String protocolVersion,
        String capabilityVersion,
        AgentAvailability status,
        boolean supportsCancel) {

    public AgentCapability toCapability() {
        return new AgentCapability(agentId, agentType, name, description, url, protocolVersion,
                capabilityVersion, status, supportsCancel);
    }
}
