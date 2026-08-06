package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrometheusClient {

    private final RestClient client;
    private final ServiceCatalogProperties catalog;

    public PrometheusClient(RestClient.Builder builder, ObservabilityProperties properties,
                            ServiceCatalogProperties catalog) {
        this(builder
                .baseUrl(trimTrailingSlash(properties.getPrometheusUrl()))
                .requestFactory(requestFactory(properties.getTimeoutSeconds()))
                .build(), catalog);
    }

    PrometheusClient(RestClient client, ServiceCatalogProperties catalog) {
        this.client = client;
        this.catalog = catalog;
    }

    public List<Sample> queryMetric(String metric, String serviceName) {
        return queryMetric(metric, serviceName, "");
    }

    public List<Sample> queryMetric(String metric, String serviceName, String additionalMatchers) {
        if (metric == null || !metric.matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) {
            throw new IllegalArgumentException("Metric name is not allowlisted");
        }
        String selector = selector(serviceName);
        String matchers = selector + (additionalMatchers == null ? "" : additionalMatchers);
        return query(metric + "{" + matchers + "}");
    }

    public List<Sample> query(String promQl) {
        if (promQl == null || promQl.isBlank()) {
            throw new IllegalArgumentException("PromQL must not be blank");
        }
        JsonNode root = client.get()
                .uri(URI.create("/api/v1/query?query=" + URLEncoder.encode(promQl, StandardCharsets.UTF_8)))
                .retrieve()
                .body(JsonNode.class);
        if (root == null || !"success".equals(root.path("status").asText())
                || !"vector".equals(root.path("data").path("resultType").asText())) {
            throw new IllegalStateException("Prometheus query returned an invalid response");
        }
        List<Sample> samples = new ArrayList<>();
        root.path("data").path("result").forEach(result -> {
            Map<String, String> labels = new LinkedHashMap<>();
            result.path("metric").fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText()));
            JsonNode value = result.path("value");
            if (value.isArray() && value.size() > 1) {
                try {
                    samples.add(new Sample(Map.copyOf(labels), Double.parseDouble(value.get(1).asText())));
                } catch (NumberFormatException ignored) {
                    // Ignore malformed samples and let the caller decide whether evidence is sufficient.
                }
            }
        });
        return samples.stream().sorted(Comparator.comparingDouble(Sample::value)).toList();
    }

    String selector(String serviceName) {
        Map<String, String> labels = catalog.require(serviceName).prometheusLabels();
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("No Prometheus labels configured for service: " + serviceName);
        }
        return labels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=\"" + escape(entry.getValue()) + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static JdkClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Prometheus URL must be configured");
        }
        return value.replaceFirst("/+$", "");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    public record Sample(Map<String, String> labels, double value) {
    }
}
