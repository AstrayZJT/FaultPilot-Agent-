package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.TraceCatalogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JaegerTraceDiagnosticsClientTest {

    @Test
    void usesFixedServerConfiguredQueryAndReturnsCuratedDependencySummary() {
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        JaegerTraceDiagnosticsClient client = new JaegerTraceDiagnosticsClient(catalog(), traceCatalog(), new ObjectMapper(),
                Duration.ofSeconds(1), (receivedEndpoint, receivedAuthorization, timeout) -> {
                    endpoint.set(receivedEndpoint);
                    authorization.set(receivedAuthorization);
                    return new TraceHttpResponse(200, response().getBytes(StandardCharsets.UTF_8), false);
                });

        JaegerTraceDiagnosticsClient.TraceInspection inspection = client.inspectSlowDependencySpans("order-service");

        assertThat(endpoint.get()).isEqualTo(URI.create(
                "http://jaeger.local:16686/api/traces?service=orders-api&limit=10&lookback=15m"));
        assertThat(authorization.get()).isEqualTo("Bearer test-only-token");
        assertThat(inspection.available()).isTrue();
        assertThat(inspection.spans()).containsExactly(
                new JaegerTraceDiagnosticsClient.TraceSpan("DEPENDENCY", 1600, "billing-api"),
                new JaegerTraceDiagnosticsClient.TraceSpan("DEPENDENCY", 1500, "inventory-api"));
        assertThat(inspection.spans().getFirst().asEvidenceData())
                .containsOnlyKeys("category", "durationMillis", "relatedService")
                .doesNotContainValue("GET /customers/42");
    }

    @Test
    void extractsOnlyMatchingTargetProcessDatabaseAndRedisSpanSummaries() {
        JaegerTraceDiagnosticsClient client = new JaegerTraceDiagnosticsClient(catalog(), traceCatalog(), new ObjectMapper(),
                Duration.ofSeconds(1), (endpoint, authorization, timeout) ->
                new TraceHttpResponse(200, response().getBytes(StandardCharsets.UTF_8), false));

        JaegerTraceDiagnosticsClient.TraceInspection database = client.inspectSlowDatabaseSpans("order-service");
        JaegerTraceDiagnosticsClient.TraceInspection redis = client.inspectRedisSpans("order-service");

        assertThat(database.spans()).containsExactly(new JaegerTraceDiagnosticsClient.TraceSpan(
                "POSTGRESQL", 1800, "postgresql"));
        assertThat(redis.spans()).containsExactly(new JaegerTraceDiagnosticsClient.TraceSpan("REDIS", 1200, "redis"));
    }

    @Test
    void doesNotCallTraceBackendWithoutCompleteServerSideConfiguration() {
        ServiceCatalogProperties services = new ServiceCatalogProperties();
        services.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "order"), "http://localhost:8081", "orders", List.of(), List.of())));
        JaegerTraceDiagnosticsClient client = new JaegerTraceDiagnosticsClient(services, new TraceCatalogProperties(),
                new ObjectMapper(), Duration.ofSeconds(1), (endpoint, authorization, timeout) -> {
                    throw new AssertionError("Trace backend should not be called without configuration");
                });

        JaegerTraceDiagnosticsClient.TraceInspection inspection = client.inspectRedisSpans("order-service");

        assertThat(inspection.configured()).isFalse();
        assertThat(inspection.available()).isFalse();
    }

    private ServiceCatalogProperties catalog() {
        ServiceCatalogProperties catalog = new ServiceCatalogProperties();
        catalog.setServices(Map.of(
                "order-service", new ServiceCatalogProperties.ServiceDefinition(
                        Map.of("job", "order"), "http://localhost:8081", "orders", "redis",
                        List.of("inventory-service", "billing-service"),
                        List.of(), null, null, null, List.of(), "primary", "orders-api"),
                "inventory-service", new ServiceCatalogProperties.ServiceDefinition(
                        Map.of("job", "inventory"), "http://localhost:18082", "orders", null, List.of(),
                        List.of(), null, null, null, List.of(), null, "inventory-api"),
                "billing-service", new ServiceCatalogProperties.ServiceDefinition(
                        Map.of("job", "billing"), "http://localhost:18083", "orders", null, List.of(),
                        List.of(), null, null, null, List.of(), null, "billing-api")));
        return catalog;
    }

    private TraceCatalogProperties traceCatalog() {
        TraceCatalogProperties catalog = new TraceCatalogProperties();
        catalog.setJaeger(Map.of("primary", new TraceCatalogProperties.JaegerDefinition(
                "http://jaeger.local:16686", "test-only-token", null, null, 15, 10)));
        return catalog;
    }

    private String response() {
        return """
                {
                  "data": [
                    {
                      "processes": {
                        "p-order": {"serviceName": "orders-api"},
                        "p-inventory": {"serviceName": "inventory-api"}
                      },
                      "spans": [
                        {
                          "traceID": "secret-trace-id",
                          "operationName": "GET /customers/42",
                          "processID": "p-order",
                          "duration": 1500000,
                          "tags": [
                            {"key": "span.kind", "value": "client"},
                            {"key": "peer.service", "value": "inventory-api"}
                          ]
                        },
                        {
                          "operationName": "POST /billing/private",
                          "processID": "p-order",
                          "duration": 1600000,
                          "tags": [
                            {"key": "span.kind", "value": "client"},
                            {"key": "peer.service", "value": "billing-api"}
                          ]
                        },
                        {
                          "operationName": "SELECT sensitive_column",
                          "processID": "p-order",
                          "duration": 1800000,
                          "tags": [{"key": "db.system", "value": "postgresql"}]
                        },
                        {
                          "operationName": "GET cache:customer:42",
                          "processID": "p-order",
                          "duration": 1200000,
                          "tags": [{"key": "db.system", "value": "redis"}]
                        },
                        {
                          "operationName": "SELECT downstream_sensitive_data",
                          "processID": "p-inventory",
                          "duration": 5000000,
                          "tags": [{"key": "db.system", "value": "postgresql"}]
                        }
                      ]
                    }
                  ]
                }
                """;
    }
}
