package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "faultpilot.agents")
public class AgentDiscoveryProperties {

    private Transport transport = Transport.LOCAL;
    private int timeoutSeconds = 5;
    private String protocolVersion = "1.0";
    private Map<AgentType, String> localVersions = defaultVersions();
    private Map<String, RemoteAgentProperties> endpoints = new LinkedHashMap<>();

    public Transport getTransport() {
        return transport;
    }

    public void setTransport(Transport transport) {
        this.transport = transport == null ? Transport.LOCAL : transport;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 30) {
            throw new IllegalArgumentException("Agent discovery timeout must be between 1 and 30 seconds");
        }
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public Map<AgentType, String> getLocalVersions() {
        return Map.copyOf(localVersions);
    }

    public void setLocalVersions(Map<AgentType, String> localVersions) {
        EnumMap<AgentType, String> copy = new EnumMap<>(AgentType.class);
        if (localVersions != null) {
            copy.putAll(localVersions);
        }
        this.localVersions = copy;
    }

    public Map<String, RemoteAgentProperties> getEndpoints() {
        return Map.copyOf(endpoints);
    }

    public void setEndpoints(Map<String, RemoteAgentProperties> endpoints) {
        this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }

    public enum Transport {
        LOCAL,
        A2A
    }

    public static class RemoteAgentProperties {
        private AgentType agentType;
        private String cardUrl;
        private boolean required;

        public AgentType getAgentType() {
            return agentType;
        }

        public void setAgentType(AgentType agentType) {
            this.agentType = agentType;
        }

        public String getCardUrl() {
            return cardUrl;
        }

        public void setCardUrl(String cardUrl) {
            this.cardUrl = cardUrl;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }
    }

    private static Map<AgentType, String> defaultVersions() {
        EnumMap<AgentType, String> versions = new EnumMap<>(AgentType.class);
        for (AgentType type : AgentType.values()) {
            versions.put(type, "local-1.0.0");
        }
        return versions;
    }
}
