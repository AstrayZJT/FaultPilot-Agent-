package com.astrayzjt.faultpilot.agent.jvm.evidence;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.DiagnosticObservation;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public final class CentralEvidenceClient {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final JvmAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public CentralEvidenceClient(JvmAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public List<RemoteEvidenceView> query(DelegationRequest task) {
        return query(task, task.availableEvidenceIds());
    }

    public List<RemoteEvidenceView> query(DelegationRequest task, List<java.util.UUID> evidenceIds) {
        List<java.util.UUID> requested = evidenceIds == null ? List.of()
                : evidenceIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return List.of();
        }
        byte[] response = send("/api/internal/evidence/query", RemoteEvidenceQueryRequest.from(task, requested),
                task.limits().deadline());
        try {
            return objectMapper.readValue(response, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, RemoteEvidenceView.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Central Evidence query returned invalid JSON", exception);
        }
    }

    public RemoteEvidenceView record(DelegationRequest task, String toolId, String toolCallId,
                                     DiagnosticObservation observation) {
        byte[] response = send("/api/internal/evidence", RemoteEvidenceWriteRequest.from(task,
                properties.getAgentId(), toolId, toolCallId, observation), task.limits().deadline());
        try {
            RemoteEvidenceReceipt receipt = objectMapper.readValue(response, RemoteEvidenceReceipt.class);
            if (receipt.evidenceId() == null || !"ACTIVE".equals(receipt.status())) {
                throw new IllegalStateException("Central Evidence receipt is invalid");
            }
            return new RemoteEvidenceView(receipt.evidenceId(), observation.evidenceType().name(), observation.source(),
                    observation.summary(), observation.data(), task.incident().timeRange().start(),
                    task.incident().timeRange().end());
        } catch (IOException exception) {
            throw new IllegalStateException("Central Evidence receipt returned invalid JSON", exception);
        }
    }

    private byte[] send(String path, Object body, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("Central Evidence request deadline has expired");
        }
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize central Evidence request", exception);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(resolve(path))
                .timeout(remaining.compareTo(Duration.ofSeconds(5)) < 0 ? remaining : Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        String token = properties.getCentralToken();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("Central Evidence response exceeded byte limit");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String summary = new String(bytes, StandardCharsets.UTF_8);
                    throw new IllegalStateException("Central Evidence API returned HTTP " + response.statusCode()
                            + ": " + summary.substring(0, Math.min(300, summary.length())));
                }
                return bytes;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Central Evidence request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Central Evidence request failed", exception);
        }
    }

    private URI resolve(String path) {
        URI base = properties.getCentralBaseUrl();
        if (base == null || !("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                || base.getFragment() != null) {
            throw new IllegalArgumentException("FaultPilot internal URL must be credential-free HTTP(S)");
        }
        try {
            String basePath = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), basePath + path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Cannot build central Evidence URL", exception);
        }
    }
}
