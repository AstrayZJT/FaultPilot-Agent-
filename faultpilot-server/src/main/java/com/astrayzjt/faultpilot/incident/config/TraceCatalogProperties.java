package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Administrator-owned Jaeger Query backends. The Agent selects only fixed trace probes.
 */
@ConfigurationProperties(prefix = "faultpilot.trace")
public class TraceCatalogProperties {

    private Map<String, JaegerDefinition> jaeger = new LinkedHashMap<>();

    public Map<String, JaegerDefinition> getJaeger() {
        return jaeger;
    }

    public void setJaeger(Map<String, JaegerDefinition> jaeger) {
        this.jaeger = jaeger == null ? new LinkedHashMap<>() : new LinkedHashMap<>(jaeger);
    }

    public JaegerDefinition requireJaeger(String reference) {
        JaegerDefinition definition = jaeger.get(reference);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown Jaeger backend in Trace Catalog: " + reference);
        }
        return definition;
    }

    public record JaegerDefinition(String baseUrl, String bearerToken, String username, String password,
                                   Integer lookbackMinutes, Integer maxTraces) {

        public JaegerDefinition {
            baseUrl = trimBaseUrl(required(baseUrl, "Jaeger Query base URL must be configured"));
            validateBaseUrl(baseUrl);
            bearerToken = normalize(bearerToken);
            username = normalize(username);
            password = normalize(password);
            if (bearerToken != null && (username != null || password != null)) {
                throw new IllegalArgumentException("Configure Jaeger bearer or basic authentication, not both");
            }
            if ((username == null) != (password == null)) {
                throw new IllegalArgumentException("Jaeger basic authentication requires both username and password");
            }
            lookbackMinutes = lookbackMinutes == null ? 15 : lookbackMinutes;
            if (lookbackMinutes < 1 || lookbackMinutes > 60) {
                throw new IllegalArgumentException("Jaeger lookback must be between 1 and 60 minutes");
            }
            maxTraces = maxTraces == null ? 10 : maxTraces;
            if (maxTraces < 1 || maxTraces > 20) {
                throw new IllegalArgumentException("Jaeger trace limit must be between 1 and 20");
            }
        }

        public String authorizationHeader() {
            if (bearerToken != null) {
                return "Bearer " + bearerToken;
            }
            if (username == null) {
                return null;
            }
            String credentials = username + ":" + password;
            return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        private static void validateBaseUrl(String value) {
            URI uri;
            try {
                uri = URI.create(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Jaeger Query base URL is invalid", exception);
            }
            if ((!("http".equalsIgnoreCase(uri.getScheme())) && !("https".equalsIgnoreCase(uri.getScheme())))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Jaeger Query base URL must be an HTTP(S) credential-free origin or path");
            }
        }

        private static String trimBaseUrl(String value) {
            return value.replaceFirst("/+$", "");
        }

        private static String required(String value, String message) {
            String normalized = normalize(value);
            if (normalized == null) {
                throw new IllegalArgumentException(message);
            }
            return normalized;
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }
}
