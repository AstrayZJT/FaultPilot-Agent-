package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.distributed.protocol.DelegationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

public final class JdkA2aAgentClient implements A2aAgentClient {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Pattern REMOTE_TASK_ID = Pattern.compile("[A-Za-z0-9._:-]{1,256}");

    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public JdkA2aAgentClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public A2aTaskSnapshot submit(URI agentUrl, String bearerToken, DelegationRequest request, Duration timeout) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(request);
        } catch (JsonProcessingException exception) {
            throw new A2aClientException("Cannot serialize A2A delegation request", false, exception);
        }
        HttpRequest.Builder builder = request(tasksUri(agentUrl), bearerToken, timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        Response response = send(builder.build());
        requireSuccess(response, "submit");
        return parse(response.body());
    }

    @Override
    public Optional<A2aTaskSnapshot> query(URI agentUrl, String bearerToken, String remoteTaskId,
                                           Duration timeout) {
        HttpRequest request = request(taskUri(agentUrl, remoteTaskId), bearerToken, timeout).GET().build();
        Response response = send(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response, "query");
        return Optional.of(parse(response.body()));
    }

    @Override
    public void cancel(URI agentUrl, String bearerToken, String remoteTaskId, Duration timeout) {
        HttpRequest request = request(taskUri(agentUrl, remoteTaskId), bearerToken, timeout).DELETE().build();
        Response response = send(request);
        if (response.statusCode() != 404) {
            requireSuccess(response, "cancel");
        }
    }

    private HttpRequest.Builder request(URI uri, String bearerToken, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new A2aClientException("A2A request deadline has expired", false);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken.trim());
        }
        return builder;
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new A2aClientException("A2A response exceeded byte limit", false);
                }
                return new Response(response.statusCode(), bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new A2aClientException("A2A request was interrupted", false, exception);
        } catch (IOException exception) {
            throw new A2aClientException("A2A request failed", true, exception);
        }
    }

    private A2aTaskSnapshot parse(byte[] body) {
        try {
            return objectMapper.readValue(body, A2aTaskSnapshot.class);
        } catch (IOException exception) {
            throw new A2aClientException("A2A response is not a valid task snapshot", false, exception);
        }
    }

    private void requireSuccess(Response response, String operation) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        boolean retryable = response.statusCode() == 408 || response.statusCode() == 425
                || response.statusCode() == 429 || response.statusCode() >= 500;
        throw new A2aClientException("A2A " + operation + " returned HTTP " + response.statusCode(), retryable);
    }

    private URI tasksUri(URI agentUrl) {
        return append(agentUrl, "/tasks");
    }

    private URI taskUri(URI agentUrl, String remoteTaskId) {
        if (remoteTaskId == null || !REMOTE_TASK_ID.matcher(remoteTaskId).matches()
                || ".".equals(remoteTaskId) || "..".equals(remoteTaskId)) {
            throw new A2aClientException("Invalid remote A2A task ID", false);
        }
        return append(agentUrl, "/tasks/" + remoteTaskId);
    }

    private URI append(URI base, String suffix) {
        if (base == null || !("http".equalsIgnoreCase(base.getScheme())
                || "https".equalsIgnoreCase(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new A2aClientException("Agent URL must be credential-free HTTP(S)", false);
        }
        String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(), path + suffix, null, null);
        } catch (URISyntaxException exception) {
            throw new A2aClientException("Cannot build A2A task URL", false, exception);
        }
    }

    private record Response(int statusCode, byte[] body) {
    }
}
