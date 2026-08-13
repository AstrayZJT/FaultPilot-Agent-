package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdkDiagnosticHttpClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsBoundedResponseWithoutFollowingRedirects() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/large", exchange -> {
            byte[] body = "x".repeat(2_048).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        JdkDiagnosticHttpClient client = new JdkDiagnosticHttpClient();

        DiagnosticHttpResponse ok = client.execute(request("/ok", 1_024));
        DiagnosticHttpResponse redirect = client.execute(request("/redirect", 1_024));

        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThatThrownBy(() -> client.execute(request("/large", 1_024)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("byte limit");
    }

    private DiagnosticHttpRequest request(String path, int maxResponseBytes) {
        return new DiagnosticHttpRequest(ToolDefinition.HttpMethod.GET,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path), Map.of(), new byte[0],
                Duration.ofSeconds(2), maxResponseBytes);
    }
}
