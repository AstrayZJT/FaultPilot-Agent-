package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.protocol.AgentCard;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class JdkAgentCardClient implements AgentCardClient {

    private static final int MAX_CARD_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public JdkAgentCardClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public AgentCard fetch(URI cardUri, Duration timeout) {
        validateCardUri(cardUri);
        HttpRequest request = HttpRequest.newBuilder(cardUri).timeout(timeout)
                .header("Accept", "application/json").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Agent Card endpoint returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body()) {
                byte[] bytes = input.readNBytes(MAX_CARD_BYTES + 1);
                if (bytes.length > MAX_CARD_BYTES) {
                    throw new IllegalStateException("Agent Card exceeded response size limit");
                }
                return objectMapper.readValue(bytes, AgentCard.class);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent Card request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Agent Card request failed", exception);
        }
    }

    private void validateCardUri(URI uri) {
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Agent Card URL must be a credential-free HTTP(S) URL");
        }
    }
}
