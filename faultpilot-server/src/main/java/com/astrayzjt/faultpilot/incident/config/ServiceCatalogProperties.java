package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
            String redisRef,
            List<String> downstreams,
            List<String> allowedActions,
            String arthasBaseUrl,
            String arthasUsername,
            String arthasPassword,
            List<String> codePackagePrefixes) {

        public ServiceDefinition(Map<String, String> prometheusLabels,
                                 String actuatorBaseUrl,
                                 String databaseRef,
                                 List<String> downstreams,
                                 List<String> allowedActions) {
            this(prometheusLabels, actuatorBaseUrl, databaseRef, null, downstreams, allowedActions,
                    null, null, null, List.of());
        }

        public ServiceDefinition(Map<String, String> prometheusLabels,
                                 String actuatorBaseUrl,
                                 String databaseRef,
                                 List<String> downstreams,
                                 List<String> allowedActions,
                                 String arthasBaseUrl,
                                 String arthasUsername,
                                 String arthasPassword,
                                 List<String> codePackagePrefixes) {
            this(prometheusLabels, actuatorBaseUrl, databaseRef, null, downstreams, allowedActions,
                    arthasBaseUrl, arthasUsername, arthasPassword, codePackagePrefixes);
        }

        @ConstructorBinding
        public ServiceDefinition {
            prometheusLabels = prometheusLabels == null ? Map.of() : Map.copyOf(prometheusLabels);
            downstreams = downstreams == null ? List.of() : List.copyOf(downstreams);
            allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
            redisRef = normalize(redisRef);
            arthasBaseUrl = normalize(arthasBaseUrl);
            arthasUsername = normalize(arthasUsername);
            arthasPassword = normalize(arthasPassword);
            codePackagePrefixes = codePackagePrefixes == null ? List.of() : codePackagePrefixes.stream()
                    .map(ServiceDefinition::normalize)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        }

        public boolean hasArthasConfiguration() {
            return arthasBaseUrl != null && arthasUsername != null && arthasPassword != null;
        }

        public boolean hasCodePackagePrefixes() {
            return !codePackagePrefixes.isEmpty();
        }

        public boolean hasRedisConfiguration() {
            return redisRef != null;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
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
