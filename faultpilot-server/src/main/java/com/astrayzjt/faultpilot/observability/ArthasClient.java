package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties.ServiceDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes one fixed, read-only Arthas command and returns a bounded application-stack summary.
 */
public class ArthasClient {

    // Arthas --all returns only thread statistics. The bounded -n form includes stack frames.
    static final String WAITING_THREADS_COMMAND = "thread --state WAITING -n 50";
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_RETURNED_THREADS = 8;
    private static final int MAX_STACK_FRAMES = 16;

    private final ServiceCatalogProperties catalog;
    private final ObjectMapper objectMapper;
    private final Duration timeout;
    private final ArthasCommandExecutor executor;

    public ArthasClient(ObservabilityProperties properties, ServiceCatalogProperties catalog,
                        ObjectMapper objectMapper) {
        this(catalog, objectMapper, Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())),
                new JdkArthasCommandExecutor(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds()))));
    }

    ArthasClient(ServiceCatalogProperties catalog, ObjectMapper objectMapper, Duration timeout,
                 ArthasCommandExecutor executor) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.executor = executor;
    }

    public ThreadInspection inspectWaitingThreads(String serviceName) {
        ServiceDefinition definition = catalog.require(serviceName);
        if (!definition.hasArthasConfiguration()) {
            return ThreadInspection.notConfigured();
        }
        if (!definition.hasCodePackagePrefixes()) {
            return ThreadInspection.missingCodePackagePrefixes();
        }
        try {
            ArthasHttpResponse response = executor.execute(apiUri(definition.arthasBaseUrl()),
                    basicAuthorization(definition.arthasUsername(), definition.arthasPassword()),
                    timeout, WAITING_THREADS_COMMAND);
            if (response.truncated() || response.statusCode() < 200 || response.statusCode() >= 300) {
                return ThreadInspection.unavailable();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || commandFailed(root)) {
                return ThreadInspection.unavailable();
            }
            return summarize(root, definition.codePackagePrefixes());
        } catch (IOException | RuntimeException exception) {
            return ThreadInspection.unavailable();
        }
    }

    private ThreadInspection summarize(JsonNode root, List<String> prefixes) {
        List<JsonNode> threadNodes = new ArrayList<>();
        List<JsonNode> threadInfoNodes = root.findValues("threadInfo");
        List<JsonNode> busyThreadNodes = root.findValues("busyThreads");
        List<JsonNode> threadNodesContainers = root.findValues("threads");
        List<JsonNode> sources = !threadInfoNodes.isEmpty() ? threadInfoNodes
                : !busyThreadNodes.isEmpty() ? busyThreadNodes : threadNodesContainers;
        if (sources.isEmpty()) {
            return ThreadInspection.unavailable();
        }
        sources.forEach(node -> collectThreadNodes(node, threadNodes));

        int waitingThreadCount = 0;
        List<BlockingThread> blockingThreads = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode thread : threadNodes) {
            String state = text(thread, "state", "threadState");
            if (!"WAITING".equalsIgnoreCase(state)) {
                continue;
            }
            waitingThreadCount++;
            List<StackFrame> frames = stackFrames(thread);
            StackFrame applicationFrame = frames.stream()
                    .filter(frame -> isApplicationFrame(frame, prefixes))
                    .findFirst()
                    .orElse(null);
            if (applicationFrame == null) {
                continue;
            }
            StackFrame blockingFrame = frames.stream().filter(ArthasClient::isBlockingFrame).findFirst().orElse(null);
            BlockingThread blockingThread = new BlockingThread(thread.path("id").asLong(thread.path("threadId").asLong(-1)),
                    defaultValue(text(thread, "name", "threadName"), "unnamed"),
                    "WAITING", location(applicationFrame),
                    blockingFrame == null ? "unknown" : operation(blockingFrame));
            if (seen.add(blockingThread.threadId() + "|" + blockingThread.sourceLocation())
                    && blockingThreads.size() < MAX_RETURNED_THREADS) {
                blockingThreads.add(blockingThread);
            }
        }
        return ThreadInspection.available(waitingThreadCount, blockingThreads);
    }

    private static boolean commandFailed(JsonNode root) {
        String state = root.path("state").asText("");
        if (state.isBlank()) {
            state = root.path("body").path("state").asText("");
        }
        state = state.toUpperCase(Locale.ROOT);
        return state.equals("FAILED") || state.equals("FAIL") || state.equals("ERROR");
    }

    private static void collectThreadNodes(JsonNode node, List<JsonNode> target) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> collectThreadNodes(value, target));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.path("stackTrace").isArray() || node.path("stack").isArray()) {
            target.add(node);
            return;
        }
        node.elements().forEachRemaining(value -> collectThreadNodes(value, target));
    }

    private static List<StackFrame> stackFrames(JsonNode thread) {
        JsonNode stackTrace = thread.path("stackTrace").isArray() ? thread.path("stackTrace") : thread.path("stack");
        List<StackFrame> frames = new ArrayList<>();
        for (JsonNode frame : stackTrace) {
            if (frames.size() >= MAX_STACK_FRAMES || !frame.isObject()) {
                break;
            }
            String className = text(frame, "className", "class");
            String methodName = text(frame, "methodName", "method");
            if (className == null || methodName == null) {
                continue;
            }
            frames.add(new StackFrame(className, methodName, text(frame, "fileName", "file"),
                    frame.path("lineNumber").asInt(-1)));
        }
        return frames;
    }

    private static boolean isApplicationFrame(StackFrame frame, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> frame.className().equals(prefix)
                || frame.className().startsWith(prefix.endsWith(".") ? prefix : prefix + "."));
    }

    private static boolean isBlockingFrame(StackFrame frame) {
        String operation = operation(frame);
        return operation.equals("java.util.concurrent.CountDownLatch.await")
                || operation.equals("java.lang.Object.wait")
                || operation.startsWith("java.util.concurrent.locks.LockSupport.park")
                || (frame.className().contains("AbstractQueuedSynchronizer")
                && (frame.methodName().startsWith("await") || frame.methodName().startsWith("park")));
    }

    private static String location(StackFrame frame) {
        String file = defaultValue(frame.fileName(), "Unknown Source");
        String line = frame.lineNumber() > 0 ? ":" + frame.lineNumber() : "";
        return frame.className() + "." + frame.methodName() + "(" + file + line + ")";
    }

    private static String operation(StackFrame frame) {
        return frame.className() + "." + frame.methodName();
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static URI apiUri(String baseUrl) {
        URI base = URI.create(baseUrl);
        if ((base.getScheme() == null || !(base.getScheme().equalsIgnoreCase("http") || base.getScheme().equalsIgnoreCase("https")))
                || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Arthas base URL must be an HTTP(S) service URL");
        }
        return URI.create(base.toString().replaceFirst("/+$", "") + "/api");
    }

    private static String basicAuthorization(String username, String password) {
        String credentials = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private record StackFrame(String className, String methodName, String fileName, int lineNumber) {
    }

    public record ThreadInspection(boolean configured, boolean codePackagePrefixesConfigured, boolean available,
                                   int waitingThreadCount, List<BlockingThread> blockingThreads) {
        public ThreadInspection {
            blockingThreads = blockingThreads == null ? List.of() : List.copyOf(blockingThreads);
        }

        static ThreadInspection notConfigured() {
            return new ThreadInspection(false, false, false, 0, List.of());
        }

        static ThreadInspection missingCodePackagePrefixes() {
            return new ThreadInspection(true, false, false, 0, List.of());
        }

        static ThreadInspection unavailable() {
            return new ThreadInspection(true, true, false, 0, List.of());
        }

        static ThreadInspection available(int waitingThreadCount, List<BlockingThread> blockingThreads) {
            return new ThreadInspection(true, true, true, waitingThreadCount, blockingThreads);
        }
    }

    public record BlockingThread(long threadId, String threadName, String state, String sourceLocation,
                                 String blockingOperation) {
        public Map<String, Object> asEvidenceData() {
            return Map.of("threadId", threadId, "threadName", threadName, "state", state,
                    "sourceLocation", sourceLocation, "blockingOperation", blockingOperation);
        }
    }

    private static final class JdkArthasCommandExecutor implements ArthasCommandExecutor {

        private final HttpClient client;

        private JdkArthasCommandExecutor(Duration timeout) {
            this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        }

        @Override
        public ArthasHttpResponse execute(URI endpoint, String authorization, Duration timeout, String command) {
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"action\":\"exec\",\"command\":\"" + command + "\"}", StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return new ArthasHttpResponse(response.statusCode(), new byte[0], false);
                    }
                    byte[] content = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    return new ArthasHttpResponse(response.statusCode(), content,
                            content.length > MAX_RESPONSE_BYTES);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Arthas request was interrupted", exception);
            } catch (IOException exception) {
                throw new IllegalStateException("Arthas request failed", exception);
            }
        }
    }
}

@FunctionalInterface
interface ArthasCommandExecutor {
    ArthasHttpResponse execute(URI endpoint, String authorization, Duration timeout, String command);
}

record ArthasHttpResponse(int statusCode, byte[] body, boolean truncated) {
    ArthasHttpResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
