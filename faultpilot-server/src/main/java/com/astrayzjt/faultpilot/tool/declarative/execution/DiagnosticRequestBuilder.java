package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DiagnosticRequestBuilder {

    private final ServiceCatalogProperties services;
    private final ObjectMapper objectMapper;

    public DiagnosticRequestBuilder(ServiceCatalogProperties services, ObjectMapper objectMapper) {
        this.services = services;
        this.objectMapper = objectMapper;
    }

    public DiagnosticHttpRequest build(ToolDefinition definition, DiagnosticEndpoint endpoint,
                                       ToolExecutionContext context, Map<String, Object> arguments) {
        context.throwIfExpired();
        validateContext(definition, context, arguments);
        ToolDefinition.Spec spec = definition.spec();
        URI baseRequestUri = resolve(endpoint, spec.endpoint().path());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (endpoint.authorization() != null) {
            headers.put("Authorization", endpoint.authorization());
        }

        Map<String, Object> query = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        if (spec.request().prometheus() != null) {
            query.put("query", prometheusQuery(spec.request().prometheus(), context.serviceName()));
        }
        spec.request().query().forEach((name, binding) -> query.put(name, resolve(binding, context)));
        spec.request().body().forEach((name, binding) -> body.put(name, resolve(binding, context)));

        URI uri = appendQuery(baseRequestUri, query);
        byte[] payload = new byte[0];
        if (!body.isEmpty()) {
            headers.put("Content-Type", "application/json");
            try {
                payload = objectMapper.writeValueAsBytes(body);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("Cannot encode declarative diagnostic request", exception);
            }
        }
        Duration remaining = Duration.between(Instant.now(), context.deadline());
        if (remaining.isNegative() || remaining.isZero()) {
            context.throwIfExpired();
        }
        Duration configured = Duration.ofSeconds(spec.limits().timeoutSeconds());
        Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
        return new DiagnosticHttpRequest(spec.endpoint().method(), uri, headers, payload, timeout,
                spec.limits().maxResponseBytes());
    }

    private void validateContext(ToolDefinition definition, ToolExecutionContext context,
                                 Map<String, Object> arguments) {
        if (definition.spec().ownerAgent() != context.agentType()) {
            throw new IllegalArgumentException("Declarative tool owner does not match execution agent");
        }
        if (context.serviceName() == null || context.serviceName().isBlank()) {
            throw new IllegalArgumentException("Diagnostic target service is required");
        }
        services.require(context.serviceName());
        if (arguments != null && !arguments.isEmpty()) {
            throw new IllegalArgumentException("Declarative tools do not accept model-generated request overrides");
        }
    }

    private String prometheusQuery(ToolDefinition.Prometheus prometheus, String serviceName) {
        Map<String, String> labels = services.require(serviceName).prometheusLabels();
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("No Prometheus labels configured for service: " + serviceName);
        }
        String selectors = labels.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=\"" + escapeMatcherValue(entry.getValue()) + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        String extra = prometheus.extraMatchers().isEmpty()
                ? "" : "," + String.join(",", prometheus.extraMatchers());
        return prometheus.metric() + "{" + selectors + extra + "}";
    }

    private Object resolve(ToolDefinition.ValueBinding binding, ToolExecutionContext context) {
        if (binding.value() != null) {
            return binding.value();
        }
        return switch (binding.source()) {
            case "task.serviceName" -> context.serviceName();
            default -> throw new IllegalArgumentException(
                    "Binding source is validated but not supported by this executor: " + binding.source());
        };
    }

    private URI resolve(DiagnosticEndpoint endpoint, String path) {
        URI resolved = endpoint.baseUri().resolve(path);
        if (!sameOrigin(endpoint.baseUri(), resolved)) {
            throw new IllegalArgumentException("Diagnostic path resolved outside the configured endpoint origin");
        }
        return resolved;
    }

    private URI appendQuery(URI uri, Map<String, Object> query) {
        if (query.isEmpty()) {
            return uri;
        }
        String encoded = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(String.valueOf(entry.getValue())))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return URI.create(uri + "?" + encoded);
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String escapeMatcherValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Prometheus label value must not be null");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
