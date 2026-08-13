package com.astrayzjt.faultpilot.agent.jvm;

import com.astrayzjt.faultpilot.agent.jvm.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "faultpilot.jvm-agent.a2a-token=a2a-token-123456",
        "faultpilot.jvm-agent.central-token=central-token-123456",
        "faultpilot.jvm-agent.public-url=http://localhost:8091/a2a",
        "faultpilot.jvm-agent.capability-version=1.0.0",
        "faultpilot.jvm-agent.services.order-service.prometheus-labels.job=faultpilot-lab-order",
        "faultpilot.jvm-agent.services.order-service.arthas-username=arthas",
        "faultpilot.jvm-agent.services.order-service.arthas-password=secret",
        "faultpilot.jvm-agent.services.order-service.code-package-prefixes[0]=com.astrayzjt.faultpilot.lab.order",
        "faultpilot.jvm-agent.thresholds.processCpuHigh=0.80",
        "faultpilot.jvm-agent.thresholds.threadPoolSaturation=0.90",
        "spring.datasource.url=jdbc:h2:mem:jvm-agent-e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "faultpilot.model.base-url=http://localhost/v1",
        "faultpilot.model.api-key=test-key",
        "faultpilot.model.model-name=qwen3.7-max"
})
class JvmAgentEndToEndTest {

    private static final String A2A_TOKEN = "a2a-token-123456";
    private static final String CENTRAL_TOKEN = "central-token-123456";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<JsonNode> EVIDENCE_WRITES = new CopyOnWriteArrayList<>();
    private static final Map<String, UUID> EVIDENCE_IDS = new ConcurrentHashMap<>();
    private static final List<String> PROMQL = new CopyOnWriteArrayList<>();
    private static final List<String> ARTHAS_COMMANDS = new CopyOnWriteArrayList<>();
    private static final UUID CROSS_DOMAIN_EVIDENCE_ID = UUID.randomUUID();
    private static HttpServer backend;

    @Autowired
    private TestRestTemplate http;

    @MockitoBean
    private ChatModel model;

    @BeforeAll
    static void resetBackendState() {
        EVIDENCE_WRITES.clear();
        EVIDENCE_IDS.clear();
        PROMQL.clear();
        ARTHAS_COMMANDS.clear();
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @DynamicPropertySource
    static void backendProperties(DynamicPropertyRegistry registry) {
        ensureBackend();
        registry.add("faultpilot.jvm-agent.central-base-url", JvmAgentEndToEndTest::backendUrl);
        registry.add("faultpilot.jvm-agent.endpoints.prometheus.base-url", JvmAgentEndToEndTest::backendUrl);
        registry.add("faultpilot.jvm-agent.services.order-service.arthas-base-url", JvmAgentEndToEndTest::backendUrl);
    }

    private static synchronized void ensureBackend() {
        if (backend != null) {
            return;
        }
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/api/v1/query", JvmAgentEndToEndTest::prometheus);
            backend.createContext("/api", JvmAgentEndToEndTest::arthas);
            backend.createContext("/api/internal/evidence/query", JvmAgentEndToEndTest::queryEvidence);
            backend.createContext("/api/internal/evidence", JvmAgentEndToEndTest::recordEvidence);
            backend.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot start JVM Agent test backend", exception);
        }
    }

    @Test
    void executesA2aJvmLoopThroughYamlToolsAndCentralEvidenceApi() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                response("{\"skillName\":\"jvm-cpu-hotspot\",\"rationale\":\"CPU symptom\"}"),
                response("{\"action\":\"CALL_TOOL\",\"toolName\":\"query_prometheus_process_cpu\"," +
                        "\"evidenceIds\":[],\"rationale\":\"Confirm process CPU\"}"),
                response("{\"action\":\"CALL_TOOL\",\"toolName\":\"query_arthas_hot_threads\"," +
                        "\"evidenceIds\":[],\"rationale\":\"Locate hot method\"}"));
        DelegationRequest request = request();

        var submitted = http.exchange("/a2a/tasks", HttpMethod.POST, entity(request), A2aTaskSnapshot.class);

        assertThat(submitted.getStatusCode().value()).isEqualTo(202);
        A2aTaskSnapshot terminal = awaitTerminal(submitted.getBody());
        assertThat(terminal.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(terminal.artifact().evidenceIds()).hasSize(2);
        assertThat(terminal.artifact().stepsUsed()).isEqualTo(2);
        assertThat(EVIDENCE_WRITES).extracting(node -> node.path("evidenceType").asText())
                .containsExactly("PROCESS_CPU_HIGH", "CPU_HOT_METHOD_FOUND");
        assertThat(EVIDENCE_WRITES).allSatisfy(node -> {
            assertThat(node.path("incidentId").asText()).isEqualTo(request.incident().incidentId().toString());
            assertThat(node.path("runId").asText()).isEqualTo(request.incident().runId().toString());
            assertThat(node.path("taskId").asText()).isEqualTo(request.taskId().toString());
        });
        assertThat(PROMQL).hasSize(1);
        assertThat(PROMQL.getFirst()).contains("process_cpu_usage{job=\"faultpilot-lab-order\"}");
        assertThat(ARTHAS_COMMANDS).containsExactly("thread -n 8");
        verify(model, times(3)).chat(any(ChatRequest.class));
    }

    private A2aTaskSnapshot awaitTerminal(A2aTaskSnapshot initial) throws InterruptedException {
        A2aTaskSnapshot current = initial;
        for (int attempt = 0; attempt < 100 && !current.status().terminal(); attempt++) {
            Thread.sleep(25);
            current = http.exchange("/a2a/tasks/{id}", HttpMethod.GET, entity(null), A2aTaskSnapshot.class,
                    initial.remoteTaskId()).getBody();
        }
        assertThat(current.status().terminal()).as("A2A JVM task reached a terminal state").isTrue();
        return current;
    }

    private HttpEntity<?> entity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(A2A_TOKEN);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    private DelegationRequest request() {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "run:1:jvm:e2e", "1.0.0",
                new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(), "order-service",
                        "order-service CPU is high", new TimeRange(now.minusSeconds(60), now)),
                "Confirm the JVM CPU hotspot and source method", List.of(CROSS_DOMAIN_EVIDENCE_ID),
                new DelegationRequest.Limits(4, now.plusSeconds(30)));
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private static void prometheus(HttpExchange exchange) throws IOException {
        PROMQL.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
        respond(exchange, 200, """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"job":"faultpilot-lab-order"},"value":[1700000000,"0.95"]}
                ]}}
                """);
    }

    private static void arthas(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        ARTHAS_COMMANDS.add(request.path("command").asText());
        respond(exchange, 200, """
                {"state":"SUCCEEDED","body":{"results":[{"type":"thread","busyThreads":[
                  {"id":17,"name":"lab-cpu-hotspot-1","state":"RUNNABLE","stackTrace":[
                    {"className":"com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager","methodName":"lambda$startCpuHotspot$8","fileName":"FaultScenarioManager.java","lineNumber":257}
                  ]}
                ]}]}}
                """);
    }

    private static void queryEvidence(HttpExchange exchange) throws IOException {
        requireCentralToken(exchange);
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode id : request.path("evidenceIds")) {
            if (CROSS_DOMAIN_EVIDENCE_ID.toString().equals(id.asText())) {
                result.add(JSON.readTree("""
                        {
                          "evidenceId":"%s",
                          "evidenceType":"REDIS_COMMAND_LATENCY_NORMAL",
                          "source":"prometheus:order-service:redis",
                          "summary":"Redis latency is normal",
                          "structuredData":{},
                          "windowStart":"2026-08-13T00:00:00Z",
                          "windowEnd":"2026-08-13T00:01:00Z"
                        }
                        """.formatted(CROSS_DOMAIN_EVIDENCE_ID)));
                continue;
            }
            EVIDENCE_WRITES.stream().filter(item -> EVIDENCE_IDS.get(item.path("toolCallId").asText())
                            .toString().equals(id.asText())).findFirst().ifPresent(result::add);
        }
        respond(exchange, 200, JSON.writeValueAsString(result));
    }

    private static void recordEvidence(HttpExchange exchange) throws IOException {
        requireCentralToken(exchange);
        JsonNode request = JSON.readTree(exchange.getRequestBody());
        EVIDENCE_WRITES.add(request);
        UUID evidenceId = EVIDENCE_IDS.computeIfAbsent(request.path("toolCallId").asText(), ignored -> UUID.randomUUID());
        respond(exchange, 200, "{\"schemaVersion\":\"faultpilot.evidence-receipt/v1\",\"evidenceId\":\""
                + evidenceId + "\",\"status\":\"ACTIVE\"}");
    }

    private static void requireCentralToken(HttpExchange exchange) throws IOException {
        if (!("Bearer " + CENTRAL_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "{\"error\":\"unauthorized\"}");
            throw new IOException("Missing central Evidence bearer token");
        }
    }

    private static String backendUrl() {
        return "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }
}
