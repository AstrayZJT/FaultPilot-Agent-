package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.RedisCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.RedisCatalogProperties.RedisDefinition;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Restricted Redis protocol client. It never accepts model-provided commands or target addresses.
 */
public class RedisDiagnosticsClient {

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_BULK_BYTES = 128 * 1024;
    private static final int MAX_NESTING = 8;
    private static final List<String> INFO_COMMANDS = List.of("memory", "stats", "clients");
    private static final java.util.Set<String> INFO_FIELDS = java.util.Set.of(
            "used_memory", "maxmemory", "mem_fragmentation_ratio", "evicted_keys",
            "connected_clients", "blocked_clients", "total_commands_processed");

    private final ServiceCatalogProperties serviceCatalog;
    private final RedisCatalogProperties redisCatalog;
    private final Duration timeout;

    public RedisDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                  RedisCatalogProperties redisCatalog,
                                  ObservabilityProperties observabilityProperties) {
        this.serviceCatalog = serviceCatalog;
        this.redisCatalog = redisCatalog;
        this.timeout = Duration.ofSeconds(Math.max(1, observabilityProperties.getTimeoutSeconds()));
    }

    public ServerInspection inspectServer(String serviceName) {
        Target target = targetFor(serviceName);
        if (target == null) {
            return ServerInspection.notConfigured();
        }
        try (RedisSession session = open(target.definition())) {
            Map<String, Long> values = new LinkedHashMap<>();
            for (String section : INFO_COMMANDS) {
                Object response = session.execute("INFO", section);
                if (!(response instanceof String body)) {
                    throw new RedisDiagnosticsException();
                }
                values.putAll(parseInfo(body));
            }
            return new ServerInspection(true, true, target.reference(), Map.copyOf(values));
        } catch (IOException | RuntimeException exception) {
            return ServerInspection.unavailable(target.reference());
        }
    }

    public SlowLogInspection readSlowLog(String serviceName) {
        Target target = targetFor(serviceName);
        if (target == null) {
            return SlowLogInspection.notConfigured();
        }
        try (RedisSession session = open(target.definition())) {
            Object response = session.execute("SLOWLOG", "GET", String.valueOf(target.definition().slowLogLimit()));
            if (!(response instanceof List<?> rows)) {
                throw new RedisDiagnosticsException();
            }
            List<SlowLogEntry> entries = new ArrayList<>();
            for (Object row : rows) {
                slowLogEntry(row).ifPresent(entries::add);
            }
            return new SlowLogInspection(true, true, target.reference(), List.copyOf(entries));
        } catch (IOException | RuntimeException exception) {
            return SlowLogInspection.unavailable(target.reference());
        }
    }

    private Target targetFor(String serviceName) {
        ServiceCatalogProperties.ServiceDefinition service = serviceCatalog.require(serviceName);
        if (!service.hasRedisConfiguration()) {
            return null;
        }
        String reference = service.redisRef();
        return new Target(reference, redisCatalog.require(reference));
    }

    private RedisSession open(RedisDefinition definition) throws IOException {
        Socket socket = new Socket();
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        socket.connect(new InetSocketAddress(definition.host(), definition.port()), timeoutMillis);
        socket.setSoTimeout(timeoutMillis);
        if (definition.tls()) {
            SSLSocket sslSocket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(socket, definition.host(), definition.port(), true);
            SSLParameters parameters = sslSocket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            sslSocket.setSSLParameters(parameters);
            sslSocket.setSoTimeout(timeoutMillis);
            sslSocket.startHandshake();
            socket = sslSocket;
        }
        RedisSession session = new RedisSession(socket);
        try {
            session.authenticate(definition);
            return session;
        } catch (RuntimeException | IOException exception) {
            session.close();
            throw exception;
        }
    }

    private Map<String, Long> parseInfo(String body) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String line : body.split("\\r?\\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String field = line.substring(0, separator);
            if (!INFO_FIELDS.contains(field)) {
                continue;
            }
            try {
                values.put(field, Long.parseLong(line.substring(separator + 1).trim()));
            } catch (NumberFormatException ignored) {
                // Some INFO values are floating-point ratios. They are not required for a safety decision.
            }
        }
        return values;
    }

    private java.util.Optional<SlowLogEntry> slowLogEntry(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() < 4) {
            return java.util.Optional.empty();
        }
        Long id = longValue(values.get(0));
        Long duration = longValue(values.get(2));
        if (id == null || duration == null || !(values.get(3) instanceof List<?> commandParts)
                || commandParts.isEmpty()) {
            return java.util.Optional.empty();
        }
        String command = commandName(commandParts.getFirst());
        if (command == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new SlowLogEntry(id, duration, command, Math.max(0, commandParts.size() - 1)));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String commandName(Object value) {
        if (!(value instanceof String command)) {
            return null;
        }
        String normalized = command.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9:_-]{0,31}") ? normalized : null;
    }

    private record Target(String reference, RedisDefinition definition) {
    }

    public record ServerInspection(boolean configured, boolean available, String redisReference,
                                   Map<String, Long> values) {
        static ServerInspection notConfigured() {
            return new ServerInspection(false, false, null, Map.of());
        }

        static ServerInspection unavailable(String reference) {
            return new ServerInspection(true, false, reference, Map.of());
        }
    }

    public record SlowLogInspection(boolean configured, boolean available, String redisReference,
                                    List<SlowLogEntry> entries) {
        static SlowLogInspection notConfigured() {
            return new SlowLogInspection(false, false, null, List.of());
        }

        static SlowLogInspection unavailable(String reference) {
            return new SlowLogInspection(true, false, reference, List.of());
        }
    }

    public record SlowLogEntry(long id, long durationMicros, String command, int argumentCount) {
        public Map<String, Object> asEvidenceData() {
            return Map.of("id", id, "durationMicros", durationMicros, "command", command,
                    "argumentCount", argumentCount);
        }
    }

    private static final class RedisSession implements AutoCloseable {
        private final Socket socket;
        private final InputStream input;
        private final OutputStream output;

        private RedisSession(Socket socket) throws IOException {
            this.socket = socket;
            this.input = new LimitedInputStream(socket.getInputStream(), MAX_RESPONSE_BYTES);
            this.output = socket.getOutputStream();
        }

        private void authenticate(RedisDefinition definition) throws IOException {
            if (definition.password() != null) {
                if (definition.username() == null) {
                    execute("AUTH", definition.password());
                } else {
                    execute("AUTH", definition.username(), definition.password());
                }
            }
            if (definition.database() > 0) {
                // SELECT changes only this diagnostic connection and avoids model-controlled database selection.
                execute("SELECT", String.valueOf(definition.database()));
            }
        }

        private Object execute(String... parts) throws IOException {
            writeCommand(output, parts);
            Object response = readResponse(input, 0);
            if (response instanceof RedisError) {
                throw new RedisDiagnosticsException();
            }
            return response;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeCommand(OutputStream output, String... parts) throws IOException {
        output.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write('\r');
            output.write('\n');
        }
        output.flush();
    }

    private static Object readResponse(InputStream input, int depth) throws IOException {
        if (depth > MAX_NESTING) {
            throw new RedisDiagnosticsException();
        }
        int prefix = input.read();
        if (prefix < 0) {
            throw new IOException("Redis connection closed");
        }
        return switch (prefix) {
            case '+' -> readLine(input);
            case '-' -> new RedisError(readLine(input));
            case ':' -> parseLong(readLine(input));
            case '$' -> readBulkString(input);
            case '*' -> readArray(input, depth + 1);
            case '_' -> {
                readLine(input);
                yield null;
            }
            case '#' -> "t".equalsIgnoreCase(readLine(input));
            case ',' -> readLine(input);
            default -> throw new RedisDiagnosticsException();
        };
    }

    private static Object readBulkString(InputStream input) throws IOException {
        long length = parseLong(readLine(input));
        if (length < 0) {
            return null;
        }
        if (length > MAX_BULK_BYTES) {
            throw new RedisDiagnosticsException();
        }
        byte[] bytes = input.readNBytes(Math.toIntExact(length));
        if (bytes.length != length || input.read() != '\r' || input.read() != '\n') {
            throw new IOException("Malformed Redis bulk response");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<Object> readArray(InputStream input, int depth) throws IOException {
        long length = parseLong(readLine(input));
        if (length < 0) {
            return List.of();
        }
        if (length > 128) {
            throw new RedisDiagnosticsException();
        }
        List<Object> values = new ArrayList<>(Math.toIntExact(length));
        for (int index = 0; index < length; index++) {
            values.add(readResponse(input, depth));
        }
        return values;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new RedisDiagnosticsException();
        }
    }

    private static String readLine(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int length = 0;
        while (length < buffer.length) {
            int next = input.read();
            if (next < 0) {
                throw new IOException("Redis connection closed");
            }
            if (next == '\r') {
                if (input.read() != '\n') {
                    throw new IOException("Malformed Redis line response");
                }
                return new String(buffer, 0, length, StandardCharsets.UTF_8);
            }
            buffer[length++] = (byte) next;
        }
        throw new RedisDiagnosticsException();
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining;

        private LimitedInputStream(InputStream input, int limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new RedisDiagnosticsException();
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new RedisDiagnosticsException();
            }
            int count = super.read(bytes, offset, Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }
    }

    private static final class RedisError {
        private final String message;

        private RedisError(String message) {
            this.message = message;
        }
    }

    private static final class RedisDiagnosticsException extends IllegalStateException {
        private RedisDiagnosticsException() {
            super("Redis diagnostic request failed");
        }
    }
}
