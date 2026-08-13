package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.distributed.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkA2aAgentClientTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void submitsDelegationToTasksEndpointWithBearerTokenAndJsonBody() throws Exception {
        DelegationRequest request = request();
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        startServer("/a2a/tasks", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(objectMapper.readTree(exchange.getRequestBody()));
            respond(exchange, 202, objectMapper.writeValueAsBytes(snapshot(request.taskId(), "remote-1",
                    DelegationStatus.SUBMITTED, null)));
        });

        A2aTaskSnapshot result = client().submit(baseUri("/a2a"), " secret-token ", request,
                Duration.ofSeconds(2));

        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isEqualTo("Bearer secret-token");
        assertThat(requestBody.get().path("taskId").asText()).isEqualTo(request.taskId().toString());
        assertThat(requestBody.get().path("idempotencyKey").asText()).isEqualTo("run:1:jvm:hash");
        assertThat(requestBody.get().path("incident").path("serviceName").asText()).isEqualTo("order-service");
        assertThat(result.remoteTaskId()).isEqualTo("remote-1");
        assertThat(result.status()).isEqualTo(DelegationStatus.SUBMITTED);
    }

    @Test
    void queriesAndCancelsRemoteTaskUsingItsTaskPath() throws Exception {
        UUID taskId = UUID.randomUUID();
        AtomicReference<String> queryMethod = new AtomicReference<>();
        AtomicReference<String> cancelMethod = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/tasks/remote-1", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                queryMethod.set(exchange.getRequestMethod());
                respond(exchange, 200, objectMapper.writeValueAsBytes(snapshot(taskId, "remote-1",
                        DelegationStatus.RUNNING, null)));
            } else {
                cancelMethod.set(exchange.getRequestMethod());
                respond(exchange, 204, new byte[0]);
            }
        });
        server.start();

        Optional<A2aTaskSnapshot> result = client().query(baseUri("/a2a/"), null, "remote-1",
                Duration.ofSeconds(2));
        client().cancel(baseUri("/a2a"), null, "remote-1", Duration.ofSeconds(2));

        assertThat(result).isPresent().get().extracting(A2aTaskSnapshot::status)
                .isEqualTo(DelegationStatus.RUNNING);
        assertThat(queryMethod.get()).isEqualTo("GET");
        assertThat(cancelMethod.get()).isEqualTo("DELETE");
    }

    @Test
    void returnsEmptyWhenQueriedRemoteTaskDoesNotExist() throws Exception {
        startServer("/tasks/missing", exchange -> respond(exchange, 404, new byte[0]));

        Optional<A2aTaskSnapshot> result = client().query(baseUri(""), null, "missing",
                Duration.ofSeconds(2));

        assertThat(result).isEmpty();
    }

    @Test
    void classifiesRetryableAndPermanentHttpFailures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/retry/tasks", exchange -> respond(exchange, 429, new byte[0]));
        server.createContext("/server/tasks", exchange -> respond(exchange, 503, new byte[0]));
        server.createContext("/invalid/tasks", exchange -> respond(exchange, 400, new byte[0]));
        server.start();
        JdkA2aAgentClient client = client();
        DelegationRequest request = request();

        assertThatThrownBy(() -> client.submit(baseUri("/retry"), null, request, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(A2aClientException.class, failure -> assertThat(failure.retryable()).isTrue());
        assertThatThrownBy(() -> client.submit(baseUri("/server"), null, request, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(A2aClientException.class, failure -> assertThat(failure.retryable()).isTrue());
        assertThatThrownBy(() -> client.submit(baseUri("/invalid"), null, request, Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(A2aClientException.class, failure -> assertThat(failure.retryable()).isFalse());
    }

    @Test
    void rejectsOversizedAndInvalidTaskResponsesAsPermanentFailures() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large/tasks", exchange -> respond(exchange, 200,
                "x".repeat(256 * 1024 + 1).getBytes(StandardCharsets.UTF_8)));
        server.createContext("/invalid/tasks", exchange -> respond(exchange, 200,
                "{\"status\":\"RUNNING\"}".getBytes(StandardCharsets.UTF_8)));
        server.start();
        JdkA2aAgentClient client = client();

        assertThatThrownBy(() -> client.submit(baseUri("/large"), null, request(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(A2aClientException.class, failure -> {
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure).hasMessageContaining("byte limit");
                });
        assertThatThrownBy(() -> client.submit(baseUri("/invalid"), null, request(), Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(A2aClientException.class, failure -> {
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure).hasMessageContaining("valid task snapshot");
                });
    }

    @Test
    void rejectsUnsafeAgentUrlsInvalidTaskIdsAndExpiredTimeoutsBeforeSending() {
        JdkA2aAgentClient client = client();

        assertThatThrownBy(() -> client.submit(URI.create("ftp://agent/a2a"), null, request(),
                Duration.ofSeconds(1))).isInstanceOf(A2aClientException.class).hasMessageContaining("HTTP(S)");
        assertThatThrownBy(() -> client.submit(URI.create("http://user:pass@agent/a2a"), null, request(),
                Duration.ofSeconds(1))).isInstanceOf(A2aClientException.class).hasMessageContaining("credential-free");
        assertThatThrownBy(() -> client.query(URI.create("http://agent/a2a"), null, "..",
                Duration.ofSeconds(1))).isInstanceOf(A2aClientException.class).hasMessageContaining("task ID");
        assertThatThrownBy(() -> client.query(URI.create("http://agent/a2a"), null, "bad/id",
                Duration.ofSeconds(1))).isInstanceOf(A2aClientException.class).hasMessageContaining("task ID");
        assertThatThrownBy(() -> client.submit(URI.create("http://agent/a2a"), null, request(), Duration.ZERO))
                .isInstanceOf(A2aClientException.class).hasMessageContaining("deadline");
    }

    private void startServer(String path, ExchangeHandler handler) throws IOException {
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

    private URI baseUri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private JdkA2aAgentClient client() {
        return new JdkA2aAgentClient(objectMapper);
    }

    private DelegationRequest request() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, taskId, "run:1:jvm:hash", "jvm-1.0.0",
                new DelegationRequest.IncidentContext(incidentId, runId, "order-service", "CPU is high",
                        new TimeRange(now.minusSeconds(60), now)), "Find the JVM CPU hotspot", List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(60)));
    }

    private A2aTaskSnapshot snapshot(UUID taskId, String remoteTaskId, DelegationStatus status,
                                     EvidenceReferenceArtifact artifact) {
        return new A2aTaskSnapshot(A2aTaskSnapshot.SCHEMA_VERSION, remoteTaskId, taskId, status, artifact,
                null, null, Instant.now());
    }

    private void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
        try (var responseBody = exchange.getResponseBody()) {
            if (body.length > 0) {
                responseBody.write(body);
            }
        }
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
