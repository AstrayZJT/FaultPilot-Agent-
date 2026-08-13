package com.astrayzjt.faultpilot.tool.declarative.execution;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfiguredEndpointCatalog implements EndpointCatalog {

    private final Map<String, DiagnosticEndpoint> endpoints;

    public ConfiguredEndpointCatalog(DeclarativeHttpProperties properties) {
        Map<String, DiagnosticEndpoint> configured = new LinkedHashMap<>();
        properties.getEndpoints().forEach((ref, definition) -> {
            if (definition != null && definition.getBaseUrl() != null && !definition.getBaseUrl().isBlank()) {
                DiagnosticEndpoint endpoint = endpoint(ref, definition);
                if (configured.put(ref, endpoint) != null) {
                    throw new IllegalArgumentException("Duplicate diagnostic endpoint: " + ref);
                }
            }
        });
        this.endpoints = Map.copyOf(configured);
    }

    @Override
    public DiagnosticEndpoint require(String endpointRef) {
        DiagnosticEndpoint endpoint = endpoints.get(endpointRef);
        if (endpoint == null) {
            throw new IllegalArgumentException("Diagnostic endpoint is not configured: " + endpointRef);
        }
        return endpoint;
    }

    private DiagnosticEndpoint endpoint(String ref, DeclarativeHttpProperties.EndpointProperties definition) {
        if (ref == null || !ref.matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Invalid diagnostic endpoint ref: " + ref);
        }
        URI uri = URI.create(definition.getBaseUrl().trim());
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Diagnostic endpoint must be a credential-free HTTP(S) base URL: " + ref);
        }
        boolean bearer = hasText(definition.getBearerToken());
        boolean basicUser = hasText(definition.getUsername());
        boolean basicPassword = hasText(definition.getPassword());
        if (bearer && (basicUser || basicPassword)) {
            throw new IllegalArgumentException("Diagnostic endpoint cannot combine bearer and basic authentication: " + ref);
        }
        if (basicUser != basicPassword) {
            throw new IllegalArgumentException("Diagnostic endpoint basic authentication requires username and password: " + ref);
        }
        String authorization = null;
        if (bearer) {
            authorization = "Bearer " + definition.getBearerToken().trim();
        } else if (basicUser) {
            String raw = definition.getUsername() + ":" + definition.getPassword();
            authorization = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
        String normalized = uri.toString().replaceFirst("/+$", "");
        return new DiagnosticEndpoint(ref, URI.create(normalized), authorization);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
