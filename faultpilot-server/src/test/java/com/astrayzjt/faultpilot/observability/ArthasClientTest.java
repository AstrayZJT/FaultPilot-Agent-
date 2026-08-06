package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ArthasClientTest {

    @Test
    void usesFixedReadOnlyCommandAndReturnsBoundedApplicationLocation() {
        AtomicReference<URI> endpoint = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> command = new AtomicReference<>();
        ArthasClient client = new ArthasClient(catalog(), new ObjectMapper(), Duration.ofSeconds(1),
                (receivedEndpoint, receivedAuthorization, timeout, receivedCommand) -> {
                    endpoint.set(receivedEndpoint);
                    authorization.set(receivedAuthorization);
                    command.set(receivedCommand);
                    return new ArthasHttpResponse(200, response().getBytes(StandardCharsets.UTF_8), false);
                });

        ArthasClient.ThreadInspection inspection = client.inspectWaitingThreads("order-service");

        assertThat(endpoint.get()).isEqualTo(URI.create("http://127.0.0.1:8563/api"));
        assertThat(authorization.get()).isEqualTo("Basic YXJ0aGFzLXVzZXI6YXJ0aGFzLXBhc3M=");
        assertThat(command.get()).isEqualTo(ArthasClient.WAITING_THREADS_COMMAND);
        assertThat(inspection.configured()).isTrue();
        assertThat(inspection.available()).isTrue();
        assertThat(inspection.waitingThreadCount()).isEqualTo(2);
        assertThat(inspection.blockingThreads()).singleElement().satisfies(thread -> {
            assertThat(thread.threadName()).isEqualTo("labBlockedExecutor-1");
            assertThat(thread.sourceLocation()).isEqualTo(
                    "com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager.lambda$startBlockedTasks$8(FaultScenarioManager.java:207)");
            assertThat(thread.blockingOperation()).isEqualTo("java.util.concurrent.CountDownLatch.await");
        });
    }

    @Test
    void doesNotCallArthasWithoutACompleteServerSideConfiguration() {
        ServiceCatalogProperties catalog = new ServiceCatalogProperties();
        catalog.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of(), "http://localhost:8081", "lab", List.of(), List.of())));
        ArthasClient client = new ArthasClient(catalog, new ObjectMapper(), Duration.ofSeconds(1),
                (endpoint, authorization, timeout, command) -> {
                    throw new AssertionError("Arthas should not be called without configuration");
                });

        ArthasClient.ThreadInspection inspection = client.inspectWaitingThreads("order-service");

        assertThat(inspection.configured()).isFalse();
        assertThat(inspection.available()).isFalse();
    }

    private ServiceCatalogProperties catalog() {
        ServiceCatalogProperties catalog = new ServiceCatalogProperties();
        catalog.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "order"), "http://localhost:8081", "lab", List.of(), List.of(),
                "http://127.0.0.1:8563", "arthas-user", "arthas-pass",
                List.of("com.astrayzjt.faultpilot.lab.order"))));
        return catalog;
    }

    private String response() {
        return """
                {
                  "state": "SUCCEEDED",
                  "body": {
                    "threadStats": {"WAITING": 2},
                    "threadInfo": [
                      {
                        "id": 31,
                        "name": "labBlockedExecutor-1",
                        "state": "WAITING",
                        "stackTrace": [
                          {"className": "java.lang.Thread", "methodName": "sleep", "fileName": "Thread.java", "lineNumber": 1},
                          {"className": "com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager", "methodName": "lambda$startBlockedTasks$8", "fileName": "FaultScenarioManager.java", "lineNumber": 207},
                          {"className": "java.util.concurrent.CountDownLatch", "methodName": "await", "fileName": "CountDownLatch.java", "lineNumber": 230}
                        ]
                      },
                      {
                        "id": 32,
                        "name": "system-waiter",
                        "state": "WAITING",
                        "stackTrace": [
                          {"className": "java.lang.Object", "methodName": "wait", "fileName": "Object.java", "lineNumber": 1}
                        ]
                      }
                    ]
                  }
                }
                """;
    }
}
