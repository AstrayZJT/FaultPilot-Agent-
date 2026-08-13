package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.tool.declarative.config.DeclarativeToolProperties;
import com.astrayzjt.faultpilot.tool.declarative.loader.DiagnosticDefinitionLoader;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDiagnosticExecutorTest {

    @Test
    void buildsPromQlFromYamlAndServiceCatalogThenMapsHighCpuEvidence() {
        ToolDefinition definition = cpuDefinition();
        ServiceCatalogProperties services = services();
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<DiagnosticHttpRequest> captured = new AtomicReference<>();
        DiagnosticHttpClient client = request -> {
            captured.set(request);
            return json(200, """
                    {"status":"success","data":{"resultType":"vector","result":[
                      {"metric":{"job":"faultpilot-lab-order"},"value":[1720000000,"0.95"]}
                    ]}}
                    """);
        };
        HttpDiagnosticExecutor executor = executor(services, objectMapper, client);

        ToolExecutionResult result = executor.execute(definition, context(), Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.PROCESS_CPU_HIGH);
        assertThat(result.data()).containsEntry("value", 0.95).containsEntry("threshold", 0.8);
        assertThat(result.source()).isEqualTo("prometheus:order-service:process_cpu_usage");
        assertThat(captured.get().method()).isEqualTo(ToolDefinition.HttpMethod.GET);
        assertThat(captured.get().uri().getPath()).isEqualTo("/api/v1/query");
        assertThat(queryParameter(captured.get().uri(), "query"))
                .isEqualTo("process_cpu_usage{job=\"faultpilot-lab-order\"}");
    }

    @Test
    void mapsNormalCpuAndRejectsModelGeneratedRequestOverrides() {
        ToolDefinition definition = cpuDefinition();
        HttpDiagnosticExecutor executor = executor(services(), new ObjectMapper(), request -> json(200, """
                {"data":{"result":[{"value":[1720000000,"0.20"]}]}}
                """));

        ToolExecutionResult result = executor.execute(definition, context(), Map.of());

        assertThat(result.evidenceType()).isEqualTo(EvidenceType.PROCESS_CPU_NORMAL);
        assertThatThrownBy(() -> executor.execute(definition, context(), Map.of(
                "query", "up", "url", "http://attacker.example")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request overrides");
    }

    @Test
    void convertsHttpAndJsonFailuresToUnavailableEvidence() {
        ToolDefinition definition = cpuDefinition();
        ToolExecutionContext context = context();
        HttpDiagnosticExecutor httpFailure = executor(services(), new ObjectMapper(),
                request -> json(503, "maintenance"));
        HttpDiagnosticExecutor malformed = executor(services(), new ObjectMapper(),
                request -> json(200, "not-json"));

        assertThat(httpFailure.execute(definition, context, Map.of()))
                .extracting(ToolExecutionResult::success, ToolExecutionResult::evidenceType,
                        ToolExecutionResult::httpStatus)
                .containsExactly(false, EvidenceType.DATA_UNAVAILABLE, 503);
        assertThat(malformed.execute(definition, context, Map.of()))
                .extracting(ToolExecutionResult::success, ToolExecutionResult::evidenceType,
                        ToolExecutionResult::summary)
                .containsExactly(false, EvidenceType.DATA_UNAVAILABLE,
                        "Diagnostic endpoint returned invalid JSON");
    }

    @Test
    void refusesCrossAgentExecutionBeforeSendingHttp() {
        AtomicReference<DiagnosticHttpRequest> captured = new AtomicReference<>();
        HttpDiagnosticExecutor executor = executor(services(), new ObjectMapper(), request -> {
            captured.set(request);
            return json(200, "{}");
        });
        ToolExecutionContext wrongAgent = new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(),
                AgentType.DATABASE_AGENT, "order-service", Instant.now().plusSeconds(10));

        assertThatThrownBy(() -> executor.execute(cpuDefinition(), wrongAgent, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
        assertThat(captured.get()).isNull();
    }

    private HttpDiagnosticExecutor executor(ServiceCatalogProperties services, ObjectMapper objectMapper,
                                              DiagnosticHttpClient client) {
        EndpointCatalog endpoints = ref -> new DiagnosticEndpoint(ref, URI.create("http://localhost:9090"), null);
        ObservabilityProperties observability = new ObservabilityProperties();
        return new HttpDiagnosticExecutor(endpoints,
                new DiagnosticRequestBuilder(services, objectMapper), client,
                new DiagnosticResponseMapper(objectMapper, new DiagnosticThresholdCatalog(observability)));
    }

    private ToolDefinition cpuDefinition() {
        var bundle = new DiagnosticDefinitionLoader(new PathMatchingResourcePatternResolver())
                .load(new DeclarativeToolProperties());
        return bundle.tools().require("query_prometheus_process_cpu");
    }

    private ServiceCatalogProperties services() {
        ServiceCatalogProperties properties = new ServiceCatalogProperties();
        properties.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "faultpilot-lab-order"), "http://localhost:8081", null, java.util.List.of(),
                java.util.List.of())));
        return properties;
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), AgentType.JVM_AGENT,
                "order-service", Instant.now().plusSeconds(10));
    }

    private DiagnosticHttpResponse json(int status, String body) {
        return new DiagnosticHttpResponse(status, Map.of("content-type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private String queryParameter(URI uri, String name) {
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
    }
}
