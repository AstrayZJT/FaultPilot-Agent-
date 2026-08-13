package com.astrayzjt.faultpilot.agent.jvm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "faultpilot.jvm-agent")
public class JvmAgentProperties {

    private String agentId = "jvm-agent";
    private String name = "FaultPilot JVM Agent";
    private String capabilityVersion = "1.0.0";
    private String protocolVersion = "1.0";
    private URI publicUrl = URI.create("http://localhost:8091/a2a");
    private String a2aToken;
    private URI centralBaseUrl = URI.create("http://localhost:8080");
    private String centralToken;
    private int taskThreads = 4;
    private Map<String, Double> thresholds = new LinkedHashMap<>();
    private Map<String, EndpointTarget> endpoints = new LinkedHashMap<>();
    private Map<String, ServiceTarget> services = new LinkedHashMap<>();

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    public void setCapabilityVersion(String capabilityVersion) {
        this.capabilityVersion = capabilityVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public URI getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(URI publicUrl) {
        this.publicUrl = publicUrl;
    }

    public String getA2aToken() {
        return a2aToken;
    }

    public void setA2aToken(String a2aToken) {
        this.a2aToken = a2aToken;
    }

    public URI getCentralBaseUrl() {
        return centralBaseUrl;
    }

    public void setCentralBaseUrl(URI centralBaseUrl) {
        this.centralBaseUrl = centralBaseUrl;
    }

    public String getCentralToken() {
        return centralToken;
    }

    public void setCentralToken(String centralToken) {
        this.centralToken = centralToken;
    }

    public int getTaskThreads() {
        return taskThreads;
    }

    public void setTaskThreads(int taskThreads) {
        if (taskThreads < 1 || taskThreads > 32) {
            throw new IllegalArgumentException("JVM Agent taskThreads must be between 1 and 32");
        }
        this.taskThreads = taskThreads;
    }

    public Map<String, ServiceTarget> getServices() {
        return Map.copyOf(services);
    }

    public Map<String, Double> getThresholds() {
        return Map.copyOf(thresholds);
    }

    public void setThresholds(Map<String, Double> thresholds) {
        this.thresholds = thresholds == null ? new LinkedHashMap<>() : new LinkedHashMap<>(thresholds);
    }

    public double requireThreshold(String name) {
        Double value = thresholds.get(name);
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Unknown JVM diagnostic threshold: " + name);
        }
        return value;
    }

    public Map<String, EndpointTarget> getEndpoints() {
        return Map.copyOf(endpoints);
    }

    public void setEndpoints(Map<String, EndpointTarget> endpoints) {
        this.endpoints = endpoints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(endpoints);
    }

    public void setServices(Map<String, ServiceTarget> services) {
        this.services = services == null ? new LinkedHashMap<>() : new LinkedHashMap<>(services);
    }

    public ServiceTarget requireService(String serviceName) {
        ServiceTarget target = services.get(serviceName);
        if (target == null) {
            throw new IllegalArgumentException("Unknown JVM diagnostic service: " + serviceName);
        }
        return target;
    }

    public static class ServiceTarget {
        private Map<String, String> prometheusLabels = new LinkedHashMap<>();
        private String arthasBaseUrl;
        private String arthasUsername;
        private String arthasPassword;
        private List<String> codePackagePrefixes = List.of();

        public Map<String, String> getPrometheusLabels() {
            return Map.copyOf(prometheusLabels);
        }

        public void setPrometheusLabels(Map<String, String> prometheusLabels) {
            this.prometheusLabels = prometheusLabels == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(prometheusLabels);
        }

        public String getArthasBaseUrl() {
            return arthasBaseUrl;
        }

        public void setArthasBaseUrl(String arthasBaseUrl) {
            this.arthasBaseUrl = arthasBaseUrl;
        }

        public String getArthasUsername() {
            return arthasUsername;
        }

        public void setArthasUsername(String arthasUsername) {
            this.arthasUsername = arthasUsername;
        }

        public String getArthasPassword() {
            return arthasPassword;
        }

        public void setArthasPassword(String arthasPassword) {
            this.arthasPassword = arthasPassword;
        }

        public List<String> getCodePackagePrefixes() {
            return codePackagePrefixes == null ? List.of() : codePackagePrefixes.stream()
                    .filter(value -> value != null && !value.isBlank()).map(String::trim).distinct().toList();
        }

        public void setCodePackagePrefixes(List<String> codePackagePrefixes) {
            this.codePackagePrefixes = codePackagePrefixes == null ? List.of() : List.copyOf(codePackagePrefixes);
        }
    }

    public static class EndpointTarget {
        private String baseUrl;
        private String bearerToken;

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
    }
}
