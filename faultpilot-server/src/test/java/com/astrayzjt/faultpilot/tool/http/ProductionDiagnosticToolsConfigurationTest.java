package com.astrayzjt.faultpilot.tool.http;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.observability.PrometheusClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient.Sample;
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

    private ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), AgentType.JVM_AGENT,
                "order-service", Instant.now().plusSeconds(10));
    }
}
