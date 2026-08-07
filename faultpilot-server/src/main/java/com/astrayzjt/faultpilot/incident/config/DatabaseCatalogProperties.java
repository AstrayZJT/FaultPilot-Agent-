package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Administrator-owned PostgreSQL diagnostic targets. Specialist Agents can only select fixed probes.
 */
@ConfigurationProperties(prefix = "faultpilot.database")
public class DatabaseCatalogProperties {

    private Map<String, PostgresDefinition> instances = new LinkedHashMap<>();

    public Map<String, PostgresDefinition> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, PostgresDefinition> instances) {
        this.instances = instances == null ? new LinkedHashMap<>() : new LinkedHashMap<>(instances);
    }

    public PostgresDefinition require(String reference) {
        PostgresDefinition definition = instances.get(reference);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown PostgreSQL instance in Database Catalog: " + reference);
        }
        return definition;
    }

    public record PostgresDefinition(String jdbcUrl, String username, String password,
                                     Integer queryTimeoutSeconds, Integer maxRows) {

        public PostgresDefinition {
            jdbcUrl = required(jdbcUrl, "PostgreSQL JDBC URL must be configured");
            String normalizedUrl = jdbcUrl.toLowerCase(Locale.ROOT);
            if (!normalizedUrl.startsWith("jdbc:postgresql:")
                    || normalizedUrl.contains("password=") || normalizedUrl.contains("user=")) {
                throw new IllegalArgumentException("PostgreSQL JDBC URL must be a credential-free PostgreSQL URL");
            }
            username = required(username, "PostgreSQL diagnostic username must be configured");
            password = normalize(password);
            queryTimeoutSeconds = queryTimeoutSeconds == null ? 3 : queryTimeoutSeconds;
            if (queryTimeoutSeconds < 1 || queryTimeoutSeconds > 10) {
                throw new IllegalArgumentException("PostgreSQL diagnostic query timeout must be between 1 and 10 seconds");
            }
            maxRows = maxRows == null ? 20 : maxRows;
            if (maxRows < 1 || maxRows > 50) {
                throw new IllegalArgumentException("PostgreSQL diagnostic max rows must be between 1 and 50");
            }
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
