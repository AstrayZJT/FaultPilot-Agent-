package com.astrayzjt.faultpilot.agent.distributed.domain;

import com.astrayzjt.faultpilot.common.domain.AgentType;

import java.net.URI;

public record AgentCapability(
        String agentId,
        AgentType agentType,
        String name,
        String description,
        URI url,
        String protocolVersion,
        String capabilityVersion,
        AgentAvailability status,
        boolean supportsCancel) {

    public AgentCapability {
        requireText(agentId, "agentId", 128);
        if (agentType == null) {
            throw new IllegalArgumentException("agentType is required");
        }
        requireText(name, "name", 256);
        requireText(description, "description", 2_000);
        boolean local = url != null && "local".equalsIgnoreCase(url.getScheme());
        boolean remote = url != null && ("http".equalsIgnoreCase(url.getScheme())
                || "https".equalsIgnoreCase(url.getScheme()));
        if (url == null || !(local || remote)
                || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
                || url.getFragment() != null) {
            throw new IllegalArgumentException("Agent URL must be a local or credential-free HTTP(S) URL");
        }
        requireText(protocolVersion, "protocolVersion", 32);
        requireText(capabilityVersion, "capabilityVersion", 128);
        if (status == null) {
            throw new IllegalArgumentException("Agent status is required");
        }
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength + " characters");
        }
    }
}
