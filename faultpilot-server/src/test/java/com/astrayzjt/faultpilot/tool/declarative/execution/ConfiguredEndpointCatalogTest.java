package com.astrayzjt.faultpilot.tool.declarative.execution;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfiguredEndpointCatalogTest {

    @Test
    void loadsCredentialFreeBaseUrlAndExternalBearerSecret() {
        DeclarativeHttpProperties properties = new DeclarativeHttpProperties();
        DeclarativeHttpProperties.EndpointProperties endpoint = new DeclarativeHttpProperties.EndpointProperties();
        endpoint.setBaseUrl("https://prometheus.internal/");
        endpoint.setBearerToken("secret-token");
        properties.setEndpoints(Map.of("prometheus", endpoint));

        DiagnosticEndpoint loaded = new ConfiguredEndpointCatalog(properties).require("prometheus");

        assertThat(loaded.baseUri().toString()).isEqualTo("https://prometheus.internal");
        assertThat(loaded.authorization()).isEqualTo("Bearer secret-token");
    }

    @Test
    void rejectsCredentialsInUrlAndMixedAuthentication() {
        DeclarativeHttpProperties properties = new DeclarativeHttpProperties();
        DeclarativeHttpProperties.EndpointProperties endpoint = new DeclarativeHttpProperties.EndpointProperties();
        endpoint.setBaseUrl("https://user:password@prometheus.internal");
        properties.setEndpoints(Map.of("prometheus", endpoint));

        assertThatThrownBy(() -> new ConfiguredEndpointCatalog(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-free");

        endpoint.setBaseUrl("https://prometheus.internal");
        endpoint.setBearerToken("token");
        endpoint.setUsername("user");
        endpoint.setPassword("password");
        assertThatThrownBy(() -> new ConfiguredEndpointCatalog(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combine bearer and basic");
    }

    @Test
    void rejectsBaseUrlsWithQueryOrFragment() {
        DeclarativeHttpProperties properties = new DeclarativeHttpProperties();
        DeclarativeHttpProperties.EndpointProperties endpoint = new DeclarativeHttpProperties.EndpointProperties();
        endpoint.setBaseUrl("https://prometheus.internal/api?target=other");
        properties.setEndpoints(Map.of("prometheus", endpoint));

        assertThatThrownBy(() -> new ConfiguredEndpointCatalog(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-free HTTP(S) base URL");

        endpoint.setBaseUrl("https://prometheus.internal/api#fragment");
        assertThatThrownBy(() -> new ConfiguredEndpointCatalog(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-free HTTP(S) base URL");
    }
}
