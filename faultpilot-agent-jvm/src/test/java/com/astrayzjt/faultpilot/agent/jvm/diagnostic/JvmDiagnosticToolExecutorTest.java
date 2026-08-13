package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JvmDiagnosticToolExecutorTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void buildsPromQlFromYamlAndServiceLabelsThenMapsThresholdEvidence() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        start("/api/v1/query", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    {"status":"success","data":{"resultType":"vector","result":[
                      {"metric":{"job":"faultpilot-lab-order"},"value":[1700000000,"0.95"]}
                    ]}}
                    """);
        });
        JvmAgentProperties properties = properties();
        JvmAgentProperties.EndpointTarget prometheus = new JvmAgentProperties.EndpointTarget();
        prometheus.setBaseUrl(baseUrl());
        properties.setEndpoints(Map.of("prometheus", prometheus));
        ToolDefinition tool = catalog().requireTool("query_prometheus_process_cpu");

        DiagnosticObservation result = new JvmDiagnosticToolExecutor(properties, new ObjectMapper())
                .execute(tool, task(), Instant.now().plusSeconds(5));

        assertThat(java.net.URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                .isEqualTo("query=process_cpu_usage{job=\"faultpilot-lab-order\"}");
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.PROCESS_CPU_HIGH);
        assertThat(result.data()).containsEntry("value", 0.95).containsEntry("threshold", 0.8);
    }

    @Test
    void executesFixedArthasCommandAndExtractsThreadMethodSourceLineAndBlockingOperation() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        start("/api", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, """
                    {"state":"SUCCEEDED","body":{"results":[{"type":"thread","busyThreads":[
                      {"id":42,"name":"lab-blocked-1","state":"WAITING","stackTrace":[
                        {"className":"java.util.concurrent.locks.LockSupport","methodName":"park","fileName":"LockSupport.java","lineNumber":211},
                        {"className":"com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager","methodName":"lambda$startBlockedTasks$9","fileName":"FaultScenarioManager.java","lineNumber":276}
                      ]}
                    ]}]}}
                    """);
        });
        JvmAgentProperties properties = properties();
        JvmAgentProperties.ServiceTarget service = service();
        service.setArthasBaseUrl(baseUrl());
        service.setArthasUsername("arthas");
        service.setArthasPassword("secret");
        properties.setServices(Map.of("order-service", service));
        ToolDefinition tool = catalog().requireTool("query_arthas_waiting_threads");

        DiagnosticObservation result = new JvmDiagnosticToolExecutor(properties, new ObjectMapper())
                .execute(tool, task(), Instant.now().plusSeconds(5));

        assertThat(authorization.get()).startsWith("Basic ");
        assertThat(body.get()).contains("thread --state WAITING -n 50");
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.BLOCKING_TASK_FOUND);
        assertThat(result.summary()).contains("FaultScenarioManager.lambda$startBlockedTasks$9")
                .contains("FaultScenarioManager.java:276").contains("LockSupport.park");
        assertThat(result.data().get("blockingThreads").toString())
                .contains("lab-blocked-1", "threadId=42", "FaultScenarioManager.java:276");
    }

    @Test
    void supportsAnArthasEndpointWithoutAuthentication() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        start("/api", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, """
                    {"state":"SUCCEEDED","body":{"results":[{"type":"thread","busyThreads":[
                      {"id":43,"name":"lab-blocked-2","state":"WAITING","stackTrace":[
                        {"className":"java.util.concurrent.locks.LockSupport","methodName":"park","fileName":"LockSupport.java","lineNumber":211},
                        {"className":"com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager","methodName":"lambda$startBlockedTasks$9","fileName":"FaultScenarioManager.java","lineNumber":276}
                      ]}
                    ]}]}}
                    """);
        });
        JvmAgentProperties properties = properties();
        JvmAgentProperties.ServiceTarget service = service();
        service.setArthasBaseUrl(baseUrl());
        properties.setServices(Map.of("order-service", service));
        ToolDefinition tool = catalog().requireTool("query_arthas_waiting_threads");

        DiagnosticObservation result = new JvmDiagnosticToolExecutor(properties, new ObjectMapper())
                .execute(tool, task(), Instant.now().plusSeconds(5));

        assertThat(authorization.get()).isNull();
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.BLOCKING_TASK_FOUND);
        assertThat(result.summary()).contains("FaultScenarioManager.java:276", "LockSupport.park");
    }

    private JvmDiagnosticCatalog catalog() {
        return new JvmDiagnosticDefinitionLoader(new org.springframework.core.io.support.PathMatchingResourcePatternResolver())
                .load();
    }

    private JvmAgentProperties properties() {
        JvmAgentProperties properties = new JvmAgentProperties();
        properties.setThresholds(Map.of("processCpuHigh", 0.8, "threadPoolSaturation", 0.9));
        properties.setServices(Map.of("order-service", service()));
        return properties;
    }

    private JvmAgentProperties.ServiceTarget service() {
        JvmAgentProperties.ServiceTarget service = new JvmAgentProperties.ServiceTarget();
        service.setPrometheusLabels(Map.of("job", "faultpilot-lab-order"));
        service.setCodePackagePrefixes(List.of("com.astrayzjt.faultpilot.lab.order"));
        return service;
    }

    private DelegationRequest task() {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "key", "1.0.0",
                new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(), "order-service",
                        "requests hang", new TimeRange(now.minusSeconds(60), now)), "Investigate JVM", List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(30)));
    }

    private void start(String path, Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
