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
    private int requestTimeoutSeconds = 5;
    private int taskTimeoutSeconds = 60;
    private int pollIntervalMillis = 250;
    private int maxRetries = 2;
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

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        if (requestTimeoutSeconds < 1 || requestTimeoutSeconds > 30) {
            throw new IllegalArgumentException("A2A request timeout must be between 1 and 30 seconds");
        }
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public int getTaskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    public void setTaskTimeoutSeconds(int taskTimeoutSeconds) {
        if (taskTimeoutSeconds < 5 || taskTimeoutSeconds > 300) {
            throw new IllegalArgumentException("A2A task timeout must be between 5 and 300 seconds");
        }
        this.taskTimeoutSeconds = taskTimeoutSeconds;
    }

    public int getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(int pollIntervalMillis) {
        if (pollIntervalMillis < 25 || pollIntervalMillis > 5_000) {
            throw new IllegalArgumentException("A2A poll interval must be between 25 and 5000 milliseconds");
        }
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        if (maxRetries < 0 || maxRetries > 2) {
            throw new IllegalArgumentException("A2A maxRetries must be between 0 and 2");
        }
        this.maxRetries = maxRetries;
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
        private String bearerToken;
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

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
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
