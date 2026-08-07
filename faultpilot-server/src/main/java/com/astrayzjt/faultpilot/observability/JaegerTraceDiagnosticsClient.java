package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties.ServiceDefinition;
import com.astrayzjt.faultpilot.incident.config.TraceCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.TraceCatalogProperties.JaegerDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads bounded Jaeger Query summaries and deliberately discards trace IDs, operation names, tag values, and payloads.
 */
public class JaegerTraceDiagnosticsClient {

    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final int MAX_RETURNED_SPANS = 12;

    private final ServiceCatalogProperties serviceCatalog;
    private final TraceCatalogProperties traceCatalog;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final TraceHttpExecutor executor;

    public JaegerTraceDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                        TraceCatalogProperties traceCatalog,
                                        ObservabilityProperties observabilityProperties,
                                        ObjectMapper objectMapper) {
        this(serviceCatalog, traceCatalog, objectMapper,
                Duration.ofSeconds(Math.max(1, observabilityProperties.getTimeoutSeconds())),
                new JdkTraceHttpExecutor(Duration.ofSeconds(Math.max(1, observabilityProperties.getTimeoutSeconds()))));
    }

    JaegerTraceDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                 TraceCatalogProperties traceCatalog,
                                 ObjectMapper objectMapper,
                                 Duration timeout,
                                 TraceHttpExecutor executor) {
        this.serviceCatalog = serviceCatalog;
        this.traceCatalog = traceCatalog;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.executor = executor;
    }

    public TraceInspection inspectSlowDependencySpans(String serviceName) {
        return inspect(serviceName, TraceProbe.DEPENDENCY);
    }

    public TraceInspection inspectSlowDatabaseSpans(String serviceName) {
        return inspect(serviceName, TraceProbe.DATABASE);
    }

    public TraceInspection inspectRedisSpans(String serviceName) {
        return inspect(serviceName, TraceProbe.REDIS);
    }

    private TraceInspection inspect(String serviceName, TraceProbe probe) {
        Target target = target(serviceName);
        if (!target.configured()) {
            return TraceInspection.notConfigured();
        }
        if (target.backend() == null) {
            return TraceInspection.unavailable(target.reference());
        }
        try {
            TraceHttpResponse response = executor.execute(apiUri(target.backend(), target.traceServiceName()),
                    target.backend().authorizationHeader(), timeout);
            if (response.truncated() || response.statusCode() < 200 || response.statusCode() >= 300) {
                return TraceInspection.unavailable(target.reference());
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.path("data").isArray()) {
                return TraceInspection.unavailable(target.reference());
            }
            return TraceInspection.available(target.reference(), summarize(root.path("data"), target, probe));
        } catch (IOException | RuntimeException exception) {
            return TraceInspection.unavailable(target.reference());
        }
    }

    private Target target(String serviceName) {
        ServiceDefinition service = serviceCatalog.require(serviceName);
        if (!service.hasTraceConfiguration()) {
            return new Target(false, null, null, null, Set.of());
        }
        try {
            Set<String> downstreams = new LinkedHashSet<>();
            for (String downstream : service.downstreams()) {
                downstreams.add(serviceCatalog.require(downstream).traceServiceNameOrDefault(downstream));
            }
            return new Target(true, service.traceRef(), traceCatalog.requireJaeger(service.traceRef()),
                    service.traceServiceNameOrDefault(serviceName), Set.copyOf(downstreams));
        } catch (IllegalArgumentException exception) {
            return new Target(true, service.traceRef(), null, null, Set.of());
        }
    }

    private List<TraceSpan> summarize(JsonNode traces, Target target, TraceProbe probe) {
        List<TraceSpan> matches = new ArrayList<>();
        traces.forEach(trace -> {
            Map<String, String> processServices = processServices(trace.path("processes"));
            trace.path("spans").forEach(span -> {
                String processService = processServices.get(span.path("processID").asText());
                if (!target.traceServiceName().equals(processService)) {
                    return;
                }
                long durationMillis = durationMillis(span.path("duration").asLong(0));
                String relatedService = probe.relatedService(span.path("tags"), target.downstreams());
                if (durationMillis <= 0 || relatedService == null) {
                    return;
                }
                matches.add(new TraceSpan(probe.category(), durationMillis, relatedService));
            });
        });
        return matches.stream()
                .sorted(Comparator.comparingLong(TraceSpan::durationMillis).reversed())
                .limit(MAX_RETURNED_SPANS)
                .toList();
    }

    private Map<String, String> processServices(JsonNode processes) {
        Map<String, String> services = new HashMap<>();
        if (!processes.isObject()) {
            return services;
        }
        processes.fields().forEachRemaining(entry -> {
            String serviceName = entry.getValue().path("serviceName").asText("");
            if (!serviceName.isBlank()) {
                services.put(entry.getKey(), serviceName);
            }
        });
        return services;
    }

    private static long durationMillis(long durationMicros) {
        if (durationMicros <= 0) {
            return 0;
        }
        return Math.max(1, (durationMicros + 999) / 1_000);
    }

    private static URI apiUri(JaegerDefinition definition, String serviceName) {
        String value = definition.baseUrl() + "/api/traces?service="
                + URLEncoder.encode(serviceName, StandardCharsets.UTF_8)
                + "&limit=" + definition.maxTraces()
                + "&lookback=" + definition.lookbackMinutes() + "m";
        return URI.create(value);
    }

    public record TraceInspection(boolean configured, boolean available, String traceReference,
                                  List<TraceSpan> spans) {
        public TraceInspection {
            spans = spans == null ? List.of() : List.copyOf(spans);
        }

        static TraceInspection notConfigured() {
            return new TraceInspection(false, false, null, List.of());
        }

        static TraceInspection unavailable(String reference) {
            return new TraceInspection(true, false, reference, List.of());
        }

        static TraceInspection available(String reference, List<TraceSpan> spans) {
            return new TraceInspection(true, true, reference, spans);
        }
    }

    public record TraceSpan(String category, long durationMillis, String relatedService) {
        public Map<String, Object> asEvidenceData() {
            return Map.of("category", category, "durationMillis", durationMillis, "relatedService", relatedService);
        }
    }

    private record Target(boolean configured, String reference, JaegerDefinition backend, String traceServiceName,
                          Set<String> downstreams) {
    }

    private enum TraceProbe {
        DATABASE("POSTGRESQL") {
            @Override
            String relatedService(JsonNode tags, Set<String> downstreams) {
                return tagEquals(tags, "db.system", "postgresql") || tagEquals(tags, "db.system.name", "postgresql")
                        ? "postgresql" : null;
            }
        },
        DEPENDENCY("DEPENDENCY") {
            @Override
            String relatedService(JsonNode tags, Set<String> downstreams) {
                if (downstreams.isEmpty() || !tagEquals(tags, "span.kind", "client")) {
                    return null;
                }
                for (String downstream : downstreams.stream().sorted().toList()) {
                    if (tagValueMatches(tags, "peer.service", Set.of(downstream))
                            || tagValueMatches(tags, "server.address", Set.of(downstream))
                            || tagValueMatches(tags, "net.peer.name", Set.of(downstream))) {
                        return downstream;
                    }
                }
                return null;
            }
        },
        REDIS("REDIS") {
            @Override
            String relatedService(JsonNode tags, Set<String> downstreams) {
                return tagEquals(tags, "db.system", "redis") || tagEquals(tags, "db.system.name", "redis")
                        ? "redis" : null;
            }
        };

        private final String category;

        TraceProbe(String category) {
            this.category = category;
        }

        abstract String relatedService(JsonNode tags, Set<String> downstreams);

        String category() {
            return category;
        }

        private static boolean tagEquals(JsonNode tags, String key, String expectedValue) {
            return tagValueMatches(tags, key, Set.of(expectedValue));
        }

        private static boolean tagValueMatches(JsonNode tags, String key, Set<String> expectedValues) {
            if (!tags.isArray()) {
                return false;
            }
            for (JsonNode tag : tags) {
                if (!key.equalsIgnoreCase(tag.path("key").asText())) {
                    continue;
                }
                String value = tag.path("value").asText();
                if (expectedValues.stream().anyMatch(expected -> expected.equalsIgnoreCase(value))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class JdkTraceHttpExecutor implements TraceHttpExecutor {

        private final HttpClient client;

        private JdkTraceHttpExecutor(Duration timeout) {
            this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        }

        @Override
        public TraceHttpResponse execute(URI endpoint, String authorization, Duration timeout) {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET();
            if (authorization != null) {
                request.header("Authorization", authorization);
            }
            try {
                HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return new TraceHttpResponse(response.statusCode(), new byte[0], false);
                    }
                    byte[] content = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    return new TraceHttpResponse(response.statusCode(), content, content.length > MAX_RESPONSE_BYTES);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Jaeger Query request was interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Jaeger Query request failed", exception);
            }
        }
    }
}

@FunctionalInterface
interface TraceHttpExecutor {
    TraceHttpResponse execute(URI endpoint, String authorization, Duration timeout);
}

record TraceHttpResponse(int statusCode, byte[] body, boolean truncated) {
    TraceHttpResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
