package com.astrayzjt.faultpilot.agent.jvm.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class JvmAgentStartupValidator implements ApplicationRunner {

    private static final Pattern SERVICE = Pattern.compile("[a-z][a-z0-9-]{1,127}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final JvmAgentProperties properties;

    public JvmAgentStartupValidator(JvmAgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        requireSecret(properties.getA2aToken(), "JVM_AGENT_A2A_TOKEN");
        requireSecret(properties.getCentralToken(), "FAULTPILOT_AGENT_API_TOKEN");
        validateBaseUri(properties.getCentralBaseUrl(), "FaultPilot internal URL", false);
        validateBaseUri(properties.getPublicUrl(), "JVM Agent public URL", true);
        properties.getEndpoints().forEach((name, target) -> {
            if (name == null || !name.matches("[a-z][a-z0-9-]{1,63}") || target == null) {
                throw new IllegalStateException("Invalid JVM diagnostic endpoint configuration");
            }
            validateBaseUri(parse(target.getBaseUrl(), "JVM diagnostic endpoint " + name),
                    "JVM diagnostic endpoint " + name, false);
        });
        if (properties.getServices().isEmpty()) {
            throw new IllegalStateException("JVM Agent requires at least one configured service");
        }
        properties.getServices().forEach(this::validateService);
    }

    private void validateService(String serviceName, JvmAgentProperties.ServiceTarget target) {
        if (serviceName == null || !SERVICE.matcher(serviceName).matches() || target == null) {
            throw new IllegalStateException("Invalid JVM diagnostic service configuration");
        }
        Map<String, String> labels = target.getPrometheusLabels();
        if (labels.isEmpty()) {
            throw new IllegalStateException("Service " + serviceName + " requires Prometheus labels");
        }
        labels.forEach((name, value) -> {
            if (name == null || !LABEL.matcher(name).matches() || value == null || value.isBlank()
                    || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalStateException("Invalid Prometheus label for service " + serviceName);
            }
        });
        if (target.getArthasBaseUrl() != null && !target.getArthasBaseUrl().isBlank()) {
            validateBaseUri(parse(target.getArthasBaseUrl(), "Arthas URL for " + serviceName),
                    "Arthas URL for " + serviceName, false);
        }
    }

    private URI parse(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        try {
            return URI.create(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(name + " is invalid", invalid);
        }
    }

    private void validateBaseUri(URI uri, String name, boolean requireA2aPath) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(name + " must be a credential-free HTTP(S) URL");
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
        if (requireA2aPath && !path.endsWith("/a2a")) {
            throw new IllegalStateException(name + " must end with /a2a");
        }
    }

    private void requireSecret(String value, String name) {
        if (value == null || value.isBlank() || value.length() < 12) {
            throw new IllegalStateException(name + " must contain at least 12 characters");
        }
    }
}
