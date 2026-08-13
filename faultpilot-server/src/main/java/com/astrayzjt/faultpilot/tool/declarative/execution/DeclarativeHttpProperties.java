package com.astrayzjt.faultpilot.tool.declarative.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "faultpilot.declarative.http")
public class DeclarativeHttpProperties {

    private Mode mode = Mode.LOCAL;
    private Map<String, EndpointProperties> endpoints = new LinkedHashMap<>();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.LOCAL : mode;
    }

    public Map<String, EndpointProperties> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, EndpointProperties> endpoints) {
        this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }

    public enum Mode {
        LOCAL,
        HTTP
    }

    public static class EndpointProperties {
        private String baseUrl;
        private String bearerToken;
        private String username;
        private String password;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getBearerToken() {
            return bearerToken;
        }

        public void setBearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
