package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class ActuatorClient {

    private final Map<String, RestClient> clients;
    private final Duration timeout;

    public ActuatorClient(ObservabilityProperties properties,
                          ServiceCatalogProperties catalog) {
        this.timeout = Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()));
        Map<String, RestClient> clientsByService = new LinkedHashMap<>();
        catalog.getServices().forEach((serviceName, definition) -> clientsByService.put(serviceName,
                RestClient.builder().baseUrl(definition.actuatorBaseUrl())
                        .requestFactory(requestFactory()).build()));
        this.clients = Map.copyOf(clientsByService);
    }

    public Map<String, Object> health(String serviceName) {
        return get(serviceName, "/actuator/health");
    }

    public Map<String, Object> metric(String serviceName, String metricName) {
        if (metricName == null || !metricName.matches("[a-zA-Z0-9_.-]+")) {
            throw new IllegalArgumentException("Actuator metric name is not allowlisted");
        }
        return get(serviceName, "/actuator/metrics/" + metricName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String serviceName, String path) {
        RestClient client = clients.get(serviceName);
        if (client == null) {
            throw new IllegalArgumentException("Unknown service in Actuator client: " + serviceName);
        }
        Map<String, Object> body = client
                .get()
                .uri(path)
                .retrieve()
                .body(Map.class);
        return body == null ? Map.of() : body;
    }

    private JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }
}
