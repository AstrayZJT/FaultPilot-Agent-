package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Administrator-owned Redis targets. A model can select only fixed read-only probes against these entries.
 */
@ConfigurationProperties(prefix = "faultpilot.redis")
public class RedisCatalogProperties {

    private Map<String, RedisDefinition> instances = new LinkedHashMap<>();

    public Map<String, RedisDefinition> getInstances() {
        return instances;
    }

    public void setInstances(Map<String, RedisDefinition> instances) {
        this.instances = instances == null ? new LinkedHashMap<>() : new LinkedHashMap<>(instances);
    }

    public RedisDefinition require(String reference) {
        RedisDefinition definition = instances.get(reference);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown Redis instance in Redis Catalog: " + reference);
        }
        return definition;
    }

    public record RedisDefinition(String host, Integer port, String username, String password,
                                  Boolean tls, Integer database, Integer slowLogLimit) {

        public RedisDefinition {
            host = normalizeRequired(host, "Redis host must be configured");
            port = port == null ? 6379 : port;
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Redis port must be between 1 and 65535");
            }
            username = normalize(username);
            password = normalize(password);
            tls = Boolean.TRUE.equals(tls);
            database = database == null ? 0 : database;
            if (database < 0 || database > 15) {
                throw new IllegalArgumentException("Redis database must be between 0 and 15");
            }
            slowLogLimit = slowLogLimit == null ? 10 : slowLogLimit;
            if (slowLogLimit < 1 || slowLogLimit > 64) {
                throw new IllegalArgumentException("Redis slow-log limit must be between 1 and 64");
            }
        }

        private static String normalizeRequired(String value, String message) {
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
