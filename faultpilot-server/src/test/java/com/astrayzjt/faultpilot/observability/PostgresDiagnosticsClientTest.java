package com.astrayzjt.faultpilot.observability;

import com.astrayzjt.faultpilot.incident.config.DatabaseCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresDiagnosticsClientTest {

    @Test
    void usesFixedStatementFingerprintQueryAndReturnsNoQueryText() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(PostgresDiagnosticsClient.SLOW_STATEMENTS_SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("statement_fingerprint")).thenReturn("923874");
        when(resultSet.getLong("calls")).thenReturn(12L);
        when(resultSet.getLong("mean_duration_millis")).thenReturn(1_200L);
        when(resultSet.getLong("max_duration_millis")).thenReturn(1_800L);

        PostgresDiagnosticsClient.SlowStatementInspection inspection = client(connection).inspectSlowStatements("order-service");

        assertThat(inspection.available()).isTrue();
        assertThat(inspection.statements()).containsExactly(new PostgresDiagnosticsClient.SlowStatement("923874", 12, 1200, 1800));
        assertThat(inspection.statements().getFirst().asEvidenceData())
                .containsOnlyKeys("statementFingerprint", "calls", "meanDurationMillis", "maxDurationMillis");
        verify(connection).setReadOnly(true);
        verify(statement).setQueryTimeout(3);
        verify(statement).setMaxRows(20);
        verify(statement).executeQuery(PostgresDiagnosticsClient.SLOW_STATEMENTS_SQL);
    }

    @Test
    void usesFixedActivityQueryAndReturnsCuratedConnectionGroups() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(PostgresDiagnosticsClient.CONNECTION_HOLDERS_SQL)).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("state")).thenReturn("active");
        when(resultSet.getString("wait_event_type")).thenReturn("Lock");
        when(resultSet.getLong("connection_count")).thenReturn(4L);
        when(resultSet.getLong("longest_age_millis")).thenReturn(5_000L);

        PostgresDiagnosticsClient.ConnectionHolderInspection inspection = client(connection)
                .inspectConnectionHolders("order-service");

        assertThat(inspection.available()).isTrue();
        assertThat(inspection.holders()).containsExactly(new PostgresDiagnosticsClient.ConnectionHolder("active", "Lock", 4, 5000));
        assertThat(inspection.holders().getFirst().asEvidenceData())
                .containsOnlyKeys("state", "waitEventType", "connectionCount", "longestAgeMillis");
        verify(statement).executeQuery(PostgresDiagnosticsClient.CONNECTION_HOLDERS_SQL);
    }

    private PostgresDiagnosticsClient client(Connection connection) {
        ServiceCatalogProperties services = new ServiceCatalogProperties();
        services.setServices(Map.of("order-service", new ServiceCatalogProperties.ServiceDefinition(
                Map.of("job", "order"), "http://localhost:8081", "orders", List.of(), List.of())));
        DatabaseCatalogProperties databases = new DatabaseCatalogProperties();
        databases.setInstances(Map.of("orders", new DatabaseCatalogProperties.PostgresDefinition(
                "jdbc:postgresql://localhost:5432/orders", "faultpilot_diagnostic", "test-only", 3, 20)));
        return new PostgresDiagnosticsClient(services, databases, (jdbcUrl, username, password) -> connection);
    }
}
