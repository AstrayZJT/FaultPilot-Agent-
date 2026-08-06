package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "faultpilot.catalog")
public class ServiceCatalogProperties {

    private Map<String, ServiceDefinition> services = new LinkedHashMap<>();

    public Map<String, ServiceDefinition> getServices() {
        return services;
    }

    public void setServices(Map<String, ServiceDefinition> services) {
        this.services = services;
    }

    public record ServiceDefinition(
            Map<String, String> prometheusLabels,
            String actuatorBaseUrl,
            String databaseRef,
            List<String> downstreams,
            List<String> allowedActions) {
        public ServiceDefinition {
            prometheusLabels = prometheusLabels == null ? Map.of() : Map.copyOf(prometheusLabels);
            downstreams = downstreams == null ? List.of() : List.copyOf(downstreams);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        }
    }

    public ServiceDefinition require(String serviceName) {
        ServiceDefinition definition = services.get(serviceName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown service in Service Catalog: " + serviceName);
        }
        return definition;
    }
}

