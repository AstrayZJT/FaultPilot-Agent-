package com.astrayzjt.faultpilot.tool.declarative.execution;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JdkDiagnosticHttpClient implements DiagnosticHttpClient {

    private final HttpClient client;

    public JdkDiagnosticHttpClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public DiagnosticHttpResponse execute(DiagnosticHttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri()).timeout(request.timeout());
        request.headers().forEach(builder::header);
        if (request.method() == com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition.HttpMethod.GET) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] body = input.readNBytes(request.maxResponseBytes() + 1);
                if (body.length > request.maxResponseBytes()) {
                    throw new IllegalStateException("Diagnostic HTTP response exceeded configured byte limit");
                }
                Map<String, String> headers = new LinkedHashMap<>();
                response.headers().map().forEach((name, values) -> headers.put(name, String.join(",", values)));
                return new DiagnosticHttpResponse(response.statusCode(), headers, body);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Diagnostic HTTP request was interrupted", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Diagnostic HTTP request failed", exception);
        }
    }
}
