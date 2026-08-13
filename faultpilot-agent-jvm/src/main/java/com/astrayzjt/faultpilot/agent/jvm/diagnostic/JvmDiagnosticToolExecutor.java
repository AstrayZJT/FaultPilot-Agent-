package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class JvmDiagnosticToolExecutor {

    private static final int MAX_STACK_FRAMES = 16;
    private final JvmAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public JvmDiagnosticToolExecutor(JvmAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy();
        this.objectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(262_144).maxNumberLength(128).build());
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public DiagnosticObservation execute(ToolDefinition tool, DelegationRequest task, Instant deadline) {
        if (!"JVM_AGENT".equals(tool.spec().ownerAgent()) || !"READ_ONLY".equals(tool.spec().riskLevel())) {
            throw new IllegalArgumentException("JVM Agent may execute only its READ_ONLY Tools");
        }
        try {
            Request request = build(tool, task, deadline);
            HttpResponse<InputStream> response = client.send(request.httpRequest(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                byte[] body = input.readNBytes(tool.spec().limits().maxResponseBytes() + 1);
                if (body.length > tool.spec().limits().maxResponseBytes()) {
                    return DiagnosticObservation.unavailable(source(tool, task),
                            "Diagnostic endpoint response exceeded the configured byte limit");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return DiagnosticObservation.unavailable(source(tool, task),
                            "Diagnostic endpoint returned HTTP " + response.statusCode());
                }
                return parse(tool, task, body);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DiagnosticObservation.unavailable(source(tool, task), "Diagnostic Tool was interrupted");
        } catch (IOException | RuntimeException exception) {
            return DiagnosticObservation.unavailable(source(tool, task), "Diagnostic endpoint is unavailable");
        }
    }

    private Request build(ToolDefinition tool, DelegationRequest task, Instant deadline) throws IOException {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new IllegalStateException("Diagnostic Tool deadline expired");
        }
        ToolDefinition.Spec spec = tool.spec();
        Endpoint endpoint = endpoint(spec.endpoint().ref(), task.incident().serviceName());
        URI uri = endpoint.baseUri().resolve(spec.endpoint().path());
        if (!sameOrigin(endpoint.baseUri(), uri)) {
            throw new IllegalArgumentException("Diagnostic Tool path escaped its configured endpoint");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        if (spec.request().prometheus() != null) {
            String selector = prometheusSelector(task.incident().serviceName());
            query.put("query", spec.request().prometheus().queryTemplate().replace("${selector}", selector));
        }
        spec.request().query().forEach((name, binding) -> query.put(name, resolve(binding, task)));
        uri = appendQuery(uri, query);
        Map<String, Object> body = new LinkedHashMap<>();
        spec.request().body().forEach((name, binding) -> body.put(name, resolve(binding, task)));
        byte[] payload = body.isEmpty() ? new byte[0] : objectMapper.writeValueAsBytes(body);
        Duration configured = Duration.ofSeconds(spec.limits().timeoutSeconds());
        Duration timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/json");
        if (endpoint.authorization() != null) {
            builder.header("Authorization", endpoint.authorization());
        }
        if (spec.endpoint().method() == ToolDefinition.HttpMethod.GET) {
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        }
        return new Request(builder.build());
    }

    private DiagnosticObservation parse(ToolDefinition tool, DelegationRequest task, byte[] body) throws IOException {
        JsonNode root = objectMapper.readTree(body);
        if (root == null) {
            return DiagnosticObservation.unavailable(source(tool, task), "Diagnostic endpoint returned empty JSON");
        }
        return switch (tool.spec().response().parser()) {
            case JSON -> parseJson(tool, task, root);
            case ARTHAS_HOT_THREADS -> parseArthas(tool, task, root, false);
            case ARTHAS_WAITING_THREADS -> parseArthas(tool, task, root, true);
        };
    }

    private DiagnosticObservation parseJson(ToolDefinition tool, DelegationRequest task, JsonNode root) {
        ToolDefinition.Response response = tool.spec().response();
        JsonNode result = resolve(root, response.resultPath());
        if (empty(result)) {
            EvidenceType type = response.emptyStatus() == null ? EvidenceType.DATA_UNAVAILABLE : response.emptyStatus();
            return new DiagnosticObservation(false, "Diagnostic endpoint returned no matching data", Map.of(),
                    type, source(tool, task));
        }
        if (response.evidence().rule() != ToolDefinition.EvidenceRule.THRESHOLD) {
            throw new IllegalArgumentException("JSON diagnostic parser currently requires a THRESHOLD Evidence rule");
        }
        List<Double> values = numericValues(result, response.valuePath(), tool.spec().limits().maxItems());
        if (values.isEmpty()) {
            return DiagnosticObservation.unavailable(source(tool, task),
                    "Diagnostic response contained no numeric samples");
        }
        double value = aggregate(values, response.aggregation());
        double threshold = properties.requireThreshold(response.evidence().thresholdFrom());
        boolean matches = value >= threshold;
        EvidenceType type = matches ? response.evidence().trueType() : response.evidence().falseType();
        String summary = matches ? response.evidence().trueSummary() : response.evidence().falseSummary();
        return new DiagnosticObservation(true, summary == null ? "Diagnostic threshold evaluated" : summary,
                Map.of("value", value, "threshold", threshold, "sampleCount", values.size()), type,
                source(tool, task));
    }

    private DiagnosticObservation parseArthas(ToolDefinition tool, DelegationRequest task, JsonNode root,
                                               boolean waitingOnly) {
        String state = root.path("state").asText(root.path("body").path("state").asText(""));
        if (Set.of("FAILED", "FAIL", "ERROR").contains(state.toUpperCase(java.util.Locale.ROOT))) {
            return DiagnosticObservation.unavailable(source(tool, task), "Arthas command failed");
        }
        List<JsonNode> threads = collectThreads(root, waitingOnly);
        List<Map<String, Object>> matches = new ArrayList<>();
        JvmAgentProperties.ServiceTarget service = properties.requireService(task.incident().serviceName());
        for (JsonNode thread : threads) {
            String stateValue = text(thread, "state", "threadState");
            if (waitingOnly && !"WAITING".equalsIgnoreCase(stateValue)) {
                continue;
            }
            List<StackFrame> frames = stackFrames(thread);
            StackFrame application = frames.stream().filter(frame -> applicationFrame(frame,
                    service.getCodePackagePrefixes())).findFirst().orElse(null);
            if (application == null) {
                continue;
            }
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("threadId", thread.path("id").asLong(thread.path("threadId").asLong(-1)));
            value.put("threadName", defaultValue(text(thread, "name", "threadName"), "unnamed"));
            value.put("state", defaultValue(stateValue, "UNKNOWN"));
            value.put("sourceLocation", location(application));
            if (waitingOnly) {
                StackFrame blocking = frames.stream().filter(this::blockingFrame).findFirst().orElse(null);
                value.put("blockingOperation", blocking == null ? "unknown" : operation(blocking));
            }
            matches.add(Map.copyOf(value));
            if (matches.size() >= tool.spec().limits().maxItems()) {
                break;
            }
        }
        if (matches.isEmpty()) {
            return new DiagnosticObservation(true, waitingOnly
                    ? "Arthas found no WAITING threads in configured application packages"
                    : "Arthas found no hot threads in configured application packages", Map.of("count", 0),
                    tool.spec().response().evidence().falseType(), source(tool, task));
        }
        Map<String, Object> first = matches.getFirst();
        String summary = tool.spec().response().evidence().trueSummary() + "; first application location: "
                + first.get("sourceLocation") + (waitingOnly ? "; blocking operation: "
                + first.get("blockingOperation") : "");
        return new DiagnosticObservation(true, summary, Map.of("count", matches.size(),
                waitingOnly ? "blockingThreads" : "hotThreads", matches),
                tool.spec().response().evidence().trueType(), source(tool, task));
    }

    private Endpoint endpoint(String ref, String serviceName) {
        if ("arthas".equals(ref)) {
            JvmAgentProperties.ServiceTarget service = properties.requireService(serviceName);
            if (blank(service.getArthasBaseUrl()) || blank(service.getArthasUsername())
                    || blank(service.getArthasPassword()) || service.getCodePackagePrefixes().isEmpty()) {
                throw new IllegalArgumentException("Arthas or application package prefixes are not configured");
            }
            URI base = validateBase(URI.create(service.getArthasBaseUrl()), "arthas");
            String raw = service.getArthasUsername() + ":" + service.getArthasPassword();
            return new Endpoint(base, "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
        }
        JvmAgentProperties.EndpointTarget configured = properties.getEndpoints().get(ref);
        if (configured == null || blank(configured.getBaseUrl())) {
            throw new IllegalArgumentException("Diagnostic endpoint is not configured: " + ref);
        }
        String authorization = blank(configured.getBearerToken()) ? null
                : "Bearer " + configured.getBearerToken().trim();
        return new Endpoint(validateBase(URI.create(configured.getBaseUrl()), ref), authorization);
    }

    private URI validateBase(URI uri, String ref) {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("Diagnostic endpoint must be credential-free HTTP(S): " + ref);
        }
        return URI.create(uri.toString().replaceFirst("/+$", ""));
    }

    private String prometheusSelector(String serviceName) {
        Map<String, String> labels = properties.requireService(serviceName).getPrometheusLabels();
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("No Prometheus labels configured for " + serviceName);
        }
        return labels.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=\"" + escapeMatcher(entry.getValue()) + "\"")
                .reduce((left, right) -> left + "," + right).orElseThrow();
    }

    private Object resolve(ToolDefinition.ValueBinding binding, DelegationRequest task) {
        if (binding.value() != null) {
            return binding.value();
        }
        if ("task.serviceName".equals(binding.source())) {
            return task.incident().serviceName();
        }
        throw new IllegalArgumentException("Unsupported Tool binding source");
    }

    private URI appendQuery(URI uri, Map<String, Object> query) {
        if (query.isEmpty()) {
            return uri;
        }
        String encoded = query.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "&" + right).orElseThrow();
        return URI.create(uri + "?" + encoded);
    }

    private JsonNode resolve(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return root;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            int bracket = segment.indexOf('[');
            current = current.path(bracket < 0 ? segment : segment.substring(0, bracket));
            while (bracket >= 0 && !current.isMissingNode()) {
                int end = segment.indexOf(']', bracket);
                current = current.path(Integer.parseInt(segment.substring(bracket + 1, end)));
                bracket = segment.indexOf('[', end + 1);
            }
        }
        return current;
    }

    private List<Double> numericValues(JsonNode result, String path, int maxItems) {
        List<Double> values = new ArrayList<>();
        if (result.isArray()) {
            for (int index = 0; index < result.size() && index < maxItems; index++) {
                addNumber(values, path == null ? result.get(index) : resolve(result.get(index), path));
            }
        } else {
            addNumber(values, path == null ? result : resolve(result, path));
        }
        return values;
    }

    private void addNumber(List<Double> values, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        try {
            double value = node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText());
            if (Double.isFinite(value)) {
                values.add(value);
            }
        } catch (NumberFormatException ignored) {
            // An all-malformed result becomes DATA_UNAVAILABLE.
        }
    }

    private double aggregate(List<Double> values, ToolDefinition.Aggregation aggregation) {
        return switch (aggregation) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            case COUNT -> values.size();
            case FIRST -> values.getFirst();
        };
    }

    private List<JsonNode> collectThreads(JsonNode root, boolean waitingOnly) {
        List<JsonNode> target = new ArrayList<>();
        List<JsonNode> primary = root.findValues(waitingOnly ? "threadInfo" : "busyThreads");
        if (primary.isEmpty()) {
            primary = root.findValues(waitingOnly ? "threads" : "threadInfo");
        }
        primary.forEach(node -> collectThreadNodes(node, target));
        return target;
    }

    private void collectThreadNodes(JsonNode node, List<JsonNode> target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> collectThreadNodes(value, target));
        } else if (node.isObject()) {
            if (node.path("stackTrace").isArray() || node.path("stack").isArray()) {
                target.add(node);
            } else {
                node.elements().forEachRemaining(value -> collectThreadNodes(value, target));
            }
        }
    }

    private List<StackFrame> stackFrames(JsonNode thread) {
        JsonNode stack = thread.path("stackTrace").isArray() ? thread.path("stackTrace") : thread.path("stack");
        List<StackFrame> frames = new ArrayList<>();
        for (JsonNode frame : stack) {
            if (frames.size() >= MAX_STACK_FRAMES || !frame.isObject()) {
                break;
            }
            String className = text(frame, "className", "class");
            String methodName = text(frame, "methodName", "method");
            if (className != null && methodName != null) {
                frames.add(new StackFrame(className, methodName, text(frame, "fileName", "file"),
                        frame.path("lineNumber").asInt(-1)));
            }
        }
        return frames;
    }

    private boolean applicationFrame(StackFrame frame, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> frame.className().equals(prefix)
                || frame.className().startsWith(prefix.endsWith(".") ? prefix : prefix + "."));
    }

    private boolean blockingFrame(StackFrame frame) {
        String value = operation(frame);
        return value.equals("java.util.concurrent.CountDownLatch.await")
                || value.equals("java.lang.Object.wait")
                || value.startsWith("java.util.concurrent.locks.LockSupport.park")
                || frame.className().contains("AbstractQueuedSynchronizer")
                && (frame.methodName().startsWith("await") || frame.methodName().startsWith("park"));
    }

    private String location(StackFrame frame) {
        return frame.className() + "." + frame.methodName() + "("
                + defaultValue(frame.fileName(), "Unknown Source")
                + (frame.lineNumber() > 0 ? ":" + frame.lineNumber() : "") + ")";
    }

    private String operation(StackFrame frame) {
        return frame.className() + "." + frame.methodName();
    }

    private String source(ToolDefinition tool, DelegationRequest task) {
        String template = tool.spec().response().evidence().sourceTemplate();
        return template == null || template.isBlank() ? tool.spec().endpoint().ref() + ":"
                + task.incident().serviceName() + ":" + tool.metadata().name()
                : template.replace("{serviceName}", task.incident().serviceName());
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean empty(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode()
                || value.isArray() && value.isEmpty() || value.isObject() && value.isEmpty()
                || value.isTextual() && value.asText().isBlank();
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private String escapeMatcher(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Prometheus label value is required");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Endpoint(URI baseUri, String authorization) {
    }

    private record Request(HttpRequest httpRequest) {
    }

    private record StackFrame(String className, String methodName, String fileName, int lineNumber) {
    }
}
