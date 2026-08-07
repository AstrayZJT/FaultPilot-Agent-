package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.DatabaseCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.DatabaseCatalogProperties.PostgresDefinition;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fixed PostgreSQL read-only diagnostics. Query text, bind values, and table data never leave the target database.
 */
public class PostgresDiagnosticsClient {

    static final String SLOW_STATEMENTS_SQL = """
            SELECT COALESCE(queryid::text, 'unknown') AS statement_fingerprint,
                   calls,
                   CEIL(mean_exec_time)::bigint AS mean_duration_millis,
                   CEIL(max_exec_time)::bigint AS max_duration_millis
            FROM pg_stat_statements
            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
              AND calls > 0
            ORDER BY mean_exec_time DESC, max_exec_time DESC
            LIMIT 20
            """;

    static final String CONNECTION_HOLDERS_SQL = """
            SELECT state,
                   COALESCE(wait_event_type, 'NONE') AS wait_event_type,
                   COUNT(*)::bigint AS connection_count,
                   FLOOR(EXTRACT(EPOCH FROM MAX(clock_timestamp() - COALESCE(xact_start, query_start))) * 1000)::bigint
                       AS longest_age_millis
            FROM pg_stat_activity
            WHERE datname = current_database()
              AND pid <> pg_backend_pid()
              AND backend_type = 'client backend'
              AND state <> 'idle'
              AND COALESCE(xact_start, query_start) IS NOT NULL
            GROUP BY state, wait_event_type
            ORDER BY longest_age_millis DESC, connection_count DESC
            LIMIT 20
            """;

    private final ServiceCatalogProperties serviceCatalog;
    private final DatabaseCatalogProperties databaseCatalog;
    private final ConnectionFactory connectionFactory;

    public PostgresDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                     DatabaseCatalogProperties databaseCatalog) {
        this(serviceCatalog, databaseCatalog, DriverManager::getConnection);
    }

    PostgresDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                              DatabaseCatalogProperties databaseCatalog,
                              ConnectionFactory connectionFactory) {
        this.serviceCatalog = serviceCatalog;
        this.databaseCatalog = databaseCatalog;
        this.connectionFactory = connectionFactory;
    }

    public SlowStatementInspection inspectSlowStatements(String serviceName) {
        Target target = target(serviceName);
        if (!target.configured()) {
            return SlowStatementInspection.notConfigured();
        }
        if (target.definition() == null) {
            return SlowStatementInspection.unavailable(target.reference());
        }
        try {
            return SlowStatementInspection.available(target.reference(), query(target.definition(), SLOW_STATEMENTS_SQL,
                    result -> new SlowStatement(
                            safeFingerprint(result.getString("statement_fingerprint")),
                            positive(result.getLong("calls")),
                            positive(result.getLong("mean_duration_millis")),
                            positive(result.getLong("max_duration_millis")))));
        } catch (SQLException | RuntimeException exception) {
            return SlowStatementInspection.unavailable(target.reference());
        }
    }

    public ConnectionHolderInspection inspectConnectionHolders(String serviceName) {
        Target target = target(serviceName);
        if (!target.configured()) {
            return ConnectionHolderInspection.notConfigured();
        }
        if (target.definition() == null) {
            return ConnectionHolderInspection.unavailable(target.reference());
        }
        try {
            return ConnectionHolderInspection.available(target.reference(), query(target.definition(), CONNECTION_HOLDERS_SQL,
                    result -> new ConnectionHolder(
                            safeLabel(result.getString("state")),
                            safeLabel(result.getString("wait_event_type")),
                            positive(result.getLong("connection_count")),
                            positive(result.getLong("longest_age_millis")))));
        } catch (SQLException | RuntimeException exception) {
            return ConnectionHolderInspection.unavailable(target.reference());
        }
    }

    private Target target(String serviceName) {
        String reference = serviceCatalog.require(serviceName).databaseRef();
        if (reference == null || reference.isBlank()) {
            return new Target(false, null, null);
        }
        try {
            return new Target(true, reference, databaseCatalog.require(reference));
        } catch (IllegalArgumentException exception) {
            return new Target(true, reference, null);
        }
    }

    private <T> List<T> query(PostgresDefinition definition, String sql, RowMapper<T> rowMapper) throws SQLException {
        try (Connection connection = connectionFactory.open(definition.jdbcUrl(), definition.username(), definition.password())) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(definition.queryTimeoutSeconds());
                statement.setMaxRows(definition.maxRows());
                try (ResultSet results = statement.executeQuery(sql)) {
                    List<T> values = new ArrayList<>();
                    while (results.next() && values.size() < definition.maxRows()) {
                        values.add(rowMapper.map(results));
                    }
                    return List.copyOf(values);
                }
            }
        }
    }

    private static long positive(long value) {
        return Math.max(0, value);
    }

    private static String safeFingerprint(String value) {
        return value != null && value.matches("-?\\d+") ? value : "unknown";
    }

    private static String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "NONE";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9_ -]", "_").trim();
        return cleaned.isBlank() ? "NONE" : cleaned.substring(0, Math.min(cleaned.length(), 64));
    }

    @FunctionalInterface
    interface ConnectionFactory {
        Connection open(String jdbcUrl, String username, String password) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet result) throws SQLException;
    }

    private record Target(boolean configured, String reference, PostgresDefinition definition) {
    }

    public record SlowStatementInspection(boolean configured, boolean available, String databaseReference,
                                          List<SlowStatement> statements) {
        public SlowStatementInspection {
            statements = statements == null ? List.of() : List.copyOf(statements);
        }

        static SlowStatementInspection notConfigured() {
            return new SlowStatementInspection(false, false, null, List.of());
        }

        static SlowStatementInspection unavailable(String reference) {
            return new SlowStatementInspection(true, false, reference, List.of());
        }

        static SlowStatementInspection available(String reference, List<SlowStatement> statements) {
            return new SlowStatementInspection(true, true, reference, statements);
        }
    }

    public record ConnectionHolderInspection(boolean configured, boolean available, String databaseReference,
                                             List<ConnectionHolder> holders) {
        public ConnectionHolderInspection {
            holders = holders == null ? List.of() : List.copyOf(holders);
        }

        static ConnectionHolderInspection notConfigured() {
            return new ConnectionHolderInspection(false, false, null, List.of());
        }

        static ConnectionHolderInspection unavailable(String reference) {
            return new ConnectionHolderInspection(true, false, reference, List.of());
        }

        static ConnectionHolderInspection available(String reference, List<ConnectionHolder> holders) {
            return new ConnectionHolderInspection(true, true, reference, holders);
        }
    }

    public record SlowStatement(String statementFingerprint, long calls, long meanDurationMillis, long maxDurationMillis) {
        public Map<String, Object> asEvidenceData() {
            return Map.of("statementFingerprint", statementFingerprint, "calls", calls,
                    "meanDurationMillis", meanDurationMillis, "maxDurationMillis", maxDurationMillis);
        }
    }

    public record ConnectionHolder(String state, String waitEventType, long connectionCount, long longestAgeMillis) {
        public Map<String, Object> asEvidenceData() {
            return Map.of("state", state, "waitEventType", waitEventType,
                    "connectionCount", connectionCount, "longestAgeMillis", longestAgeMillis);
        }
    }
}
