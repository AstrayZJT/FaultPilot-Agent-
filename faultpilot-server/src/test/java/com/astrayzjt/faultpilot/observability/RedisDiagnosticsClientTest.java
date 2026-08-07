package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.RedisCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class RedisDiagnosticsClientTest {

    @Test
    void usesOnlyFixedReadOnlyCommandsAndRedactsSlowLogArguments() throws Exception {
        try (ServerSocket server = new ServerSocket(0); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> served = executor.submit(() -> serve(server));
            RedisDiagnosticsClient client = client(server.getLocalPort());

            RedisDiagnosticsClient.ServerInspection serverInspection = client.inspectServer("order-service");
            RedisDiagnosticsClient.SlowLogInspection slowLog = client.readSlowLog("order-service");

            served.get();
            assertThat(serverInspection.available()).isTrue();
            assertThat(serverInspection.values()).containsEntry("used_memory", 1024L)
                    .containsEntry("evicted_keys", 2L).containsEntry("connected_clients", 3L);
            assertThat(slowLog.entries()).singleElement().satisfies(entry -> {
                assertThat(entry.command()).isEqualTo("GET");
                assertThat(entry.durationMicros()).isEqualTo(20_000L);
                assertThat(entry.argumentCount()).isEqualTo(1);
                assertThat(entry.asEvidenceData()).doesNotContainValue("customer:secret-key");
            });
        }
    }

    private RedisDiagnosticsClient client(int port) {
        ServiceCatalogProperties serviceCatalog = new ServiceCatalogProperties();
        serviceCatalog.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "order"), "http://localhost:8081", null, "lab-redis", List.of(), List.of(),
                null, null, null, List.of())));
        RedisCatalogProperties redisCatalog = new RedisCatalogProperties();
        redisCatalog.setInstances(Map.of("lab-redis", new RedisCatalogProperties.RedisDefinition(
                "localhost", port, "diagnostic", "not-a-real-secret", false, 0, 4)));
        return new RedisDiagnosticsClient(serviceCatalog, redisCatalog, new ObservabilityProperties());
    }

    private void serve(ServerSocket server) {
        try {
            serveInfoConnection(server.accept());
            serveSlowLogConnection(server.accept());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void serveInfoConnection(Socket socket) throws IOException {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            assertThat(readCommand(input)).containsExactly("AUTH", "diagnostic", "not-a-real-secret");
            writeSimple(output, "OK");
            assertThat(readCommand(input)).containsExactly("INFO", "memory");
            writeBulk(output, "used_memory:1024\r\nmaxmemory:2048\r\n");
            assertThat(readCommand(input)).containsExactly("INFO", "stats");
            writeBulk(output, "evicted_keys:2\r\n");
            assertThat(readCommand(input)).containsExactly("INFO", "clients");
            writeBulk(output, "connected_clients:3\r\nblocked_clients:1\r\n");
        }
    }

    private void serveSlowLogConnection(Socket socket) throws IOException {
        try (socket; InputStream input = socket.getInputStream(); OutputStream output = socket.getOutputStream()) {
            assertThat(readCommand(input)).containsExactly("AUTH", "diagnostic", "not-a-real-secret");
            writeSimple(output, "OK");
            assertThat(readCommand(input)).containsExactly("SLOWLOG", "GET", "4");
            write(output, "*1\r\n*6\r\n:7\r\n:0\r\n:20000\r\n*2\r\n$3\r\nGET\r\n$19\r\ncustomer:secret-key\r\n$9\r\n127.0.0.1\r\n$0\r\n\r\n");
        }
    }

    private List<String> readCommand(InputStream input) throws IOException {
        int marker = input.read();
        if (marker != '*') {
            throw new IOException("Expected Redis array command");
        }
        int count = Integer.parseInt(readLine(input));
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (input.read() != '$') {
                throw new IOException("Expected Redis bulk command part");
            }
            int length = Integer.parseInt(readLine(input));
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length || input.read() != '\r' || input.read() != '\n') {
                throw new IOException("Invalid Redis bulk command part");
            }
            values.add(new String(bytes, StandardCharsets.UTF_8));
        }
        return List.copyOf(values);
    }

    private void writeSimple(OutputStream output, String value) throws IOException {
        write(output, "+" + value + "\r\n");
    }

    private void writeBulk(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        write(output, "$" + bytes.length + "\r\n" + value + "\r\n");
    }

    private void write(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    private String readLine(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0 && value != '\r') {
            output.write(value);
        }
        if (value < 0 || input.read() != '\n') {
            throw new IOException("Invalid Redis line");
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
