package com.astrayzjt.faultpilot.tool.http;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.observability.ArthasClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient.Sample;
import com.astrayzjt.faultpilot.observability.PostgresDiagnosticsClient;
import com.astrayzjt.faultpilot.observability.JaegerTraceDiagnosticsClient;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionDiagnosticToolsConfigurationTest {

    @Test
    void mapsHighPrometheusCpuToEvidenceWithoutAllowingQueryArguments() {
        PrometheusClient client = mock(PrometheusClient.class);
        when(client.queryMetric("process_cpu_usage", "order-service"))
                .thenReturn(List.of(new Sample(Map.of("job", "faultpilot-lab-order"), 0.95)));
        ObservabilityProperties properties = new ObservabilityProperties();
        DiagnosticTool<Map<String, Object>> tool =
                new ProductionDiagnosticToolsConfiguration().queryPrometheusProcessCpu(client, properties);

        var result = tool.execute(Map.of("query", "up"), context());

        assertThat(tool.owner()).isEqualTo(AgentType.JVM_AGENT);
        assertThat(tool.risk().name()).isEqualTo("READ_ONLY");
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.PROCESS_CPU_HIGH);
        assertThat(result.data()).containsEntry("value", 0.95);
    }

    @Test
    void mapsCuratedArthasLocationToBlockingTaskEvidence() {
        ArthasClient client = mock(ArthasClient.class);
        ArthasClient.BlockingThread thread = new ArthasClient.BlockingThread(31, "labBlockedExecutor-1", "WAITING",
                "com.example.orders.OrderWorker.awaitCapacity(OrderWorker.java:88)",
                "java.util.concurrent.CountDownLatch.await");
        when(client.inspectWaitingThreads("order-service")).thenReturn(
                new ArthasClient.ThreadInspection(true, true, true, 4, List.of(thread)));
        DiagnosticTool<Map<String, Object>> tool =
                new ProductionDiagnosticToolsConfiguration().queryArthasWaitingThreads(client);

        var result = tool.execute(Map.of("command", "ognl '@java.lang.Runtime@getRuntime().exec()'"), context());

        assertThat(tool.owner()).isEqualTo(AgentType.JVM_AGENT);
        assertThat(tool.risk().name()).isEqualTo("READ_ONLY");
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.BLOCKING_TASK_FOUND);
        assertThat(result.summary()).contains("OrderWorker.java:88");
        assertThat(result.data()).containsEntry("waitingThreadCount", 4);
    }

    @Test
    void mapsCuratedArthasHotMethodToCpuEvidence() {
        ArthasClient client = mock(ArthasClient.class);
        ArthasClient.HotThread thread = new ArthasClient.HotThread(41, "cpu-worker", "RUNNABLE",
                "com.example.orders.PricingLoop.recalculate(PricingLoop.java:49)");
        when(client.inspectHotThreads("order-service")).thenReturn(
                new ArthasClient.HotThreadInspection(true, true, true, List.of(thread)));
        DiagnosticTool<Map<String, Object>> tool =
                new ProductionDiagnosticToolsConfiguration().queryArthasHotThreads(client);

        var result = tool.execute(Map.of(), context());

        assertThat(result.evidenceType()).isEqualTo(EvidenceType.CPU_HOT_METHOD_FOUND);
        assertThat(result.summary()).contains("PricingLoop.java:49");
    }

    @Test
    void mapsCuratedPostgresFingerprintToSlowSqlEvidence() {
        PostgresDiagnosticsClient client = mock(PostgresDiagnosticsClient.class);
        PostgresDiagnosticsClient.SlowStatement statement = new PostgresDiagnosticsClient.SlowStatement(
                "918273", 18, 1_250, 1_600);
        when(client.inspectSlowStatements("order-service")).thenReturn(
                new PostgresDiagnosticsClient.SlowStatementInspection(true, true, "orders", List.of(statement)));
        ObservabilityProperties properties = new ObservabilityProperties();
        DiagnosticTool<Map<String, Object>> tool = new ProductionDiagnosticToolsConfiguration()
                .inspectPostgresSlowStatements(client, properties);

        var result = tool.execute(Map.of("sql", "select * from customer"), context());

        assertThat(tool.owner()).isEqualTo(AgentType.DATABASE_AGENT);
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.SLOW_SQL_FOUND);
        assertThat(result.data().toString()).contains("918273").doesNotContain("select * from customer");
    }

    @Test
    void mapsCuratedPostgresHolderToConnectionHoldingEvidence() {
        PostgresDiagnosticsClient client = mock(PostgresDiagnosticsClient.class);
        PostgresDiagnosticsClient.ConnectionHolder holder = new PostgresDiagnosticsClient.ConnectionHolder(
                "active", "Lock", 4, 5_000);
        when(client.inspectConnectionHolders("order-service")).thenReturn(
                new PostgresDiagnosticsClient.ConnectionHolderInspection(true, true, "orders", List.of(holder)));
        ObservabilityProperties properties = new ObservabilityProperties();
        DiagnosticTool<Map<String, Object>> tool = new ProductionDiagnosticToolsConfiguration()
                .inspectPostgresConnectionHolders(client, properties);

        var result = tool.execute(Map.of(), context());

        assertThat(result.evidenceType()).isEqualTo(EvidenceType.CONNECTION_HOLDING_QUERY_FOUND);
        assertThat(result.data().toString()).contains("longestAgeMillis=5000");
    }

    @Test
    void mapsCuratedTraceDatabaseSpanToCorrelationEvidence() {
        JaegerTraceDiagnosticsClient client = mock(JaegerTraceDiagnosticsClient.class);
        JaegerTraceDiagnosticsClient.TraceSpan span = new JaegerTraceDiagnosticsClient.TraceSpan(
                "POSTGRESQL", 1_500, "postgresql");
        when(client.inspectSlowDatabaseSpans("order-service")).thenReturn(
                new JaegerTraceDiagnosticsClient.TraceInspection(true, true, "primary", List.of(span)));
        DiagnosticTool<Map<String, Object>> tool = new ProductionDiagnosticToolsConfiguration()
                .inspectTraceSlowDatabaseSpans(client, new ObservabilityProperties());

        var result = tool.execute(Map.of("url", "http://untrusted"), context());

        assertThat(result.evidenceType()).isEqualTo(EvidenceType.API_AND_SQL_TIME_CORRELATED);
        assertThat(result.data().toString()).doesNotContain("http://untrusted");
    }

    @Test
    void mapsCuratedTraceDependencyAndRedisSpansToEvidence() {
        JaegerTraceDiagnosticsClient client = mock(JaegerTraceDiagnosticsClient.class);
        when(client.inspectSlowDependencySpans("order-service")).thenReturn(
                new JaegerTraceDiagnosticsClient.TraceInspection(true, true, "primary", List.of(
                        new JaegerTraceDiagnosticsClient.TraceSpan("DEPENDENCY", 1_500, "inventory-service"))));
        when(client.inspectRedisSpans("order-service")).thenReturn(
                new JaegerTraceDiagnosticsClient.TraceInspection(true, true, "primary", List.of(
                        new JaegerTraceDiagnosticsClient.TraceSpan("REDIS", 1_500, "redis"))));
        ProductionDiagnosticToolsConfiguration configuration = new ProductionDiagnosticToolsConfiguration();

        var dependency = configuration.inspectTraceSlowDependencySpans(client, new ObservabilityProperties())
                .execute(Map.of(), context());
        var redis = configuration.inspectTraceRedisSpans(client, new ObservabilityProperties())
                .execute(Map.of(), context());

        assertThat(dependency.evidenceType()).isEqualTo(EvidenceType.SLOW_CHILD_SPAN_FOUND);
        assertThat(redis.evidenceType()).isEqualTo(EvidenceType.REDIS_TRACE_LATENCY_CORRELATED);
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), AgentType.JVM_AGENT,
                "order-service", Instant.now().plusSeconds(10));
    }
}
