package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.declarative.config.DeclarativeToolProperties;
import com.astrayzjt.faultpilot.tool.declarative.loader.DiagnosticDefinitionLoader;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDiagnosticToolExecutorTest {

    @Test
    void delegatesYamlToolNameToExistingJavaToolDuringMigration() {
        DiagnosticTool<Map<String, Object>> legacy = new DiagnosticTool<>() {
            @Override public String name() { return "query_prometheus_process_cpu"; }
            @Override public AgentType owner() { return AgentType.JVM_AGENT; }
            @Override public ToolRisk risk() { return ToolRisk.READ_ONLY; }
            @Override @SuppressWarnings("unchecked") public Class<Map<String, Object>> argumentType() {
                return (Class<Map<String, Object>>) (Class<?>) Map.class;
            }
            @Override public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
                return new ToolResult(true, "legacy result", Map.of("value", 0.91),
                        EvidenceType.PROCESS_CPU_HIGH, "prometheus:order-service:process_cpu_usage");
            }
        };
        var definition = new DiagnosticDefinitionLoader(new PathMatchingResourcePatternResolver())
                .load(new DeclarativeToolProperties()).tools().require(legacy.name());
        LocalDiagnosticToolExecutor executor = new LocalDiagnosticToolExecutor(new ToolRegistry(List.of(legacy)));

        ToolExecutionResult result = executor.execute(definition, context(), Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.evidenceType()).isEqualTo(EvidenceType.PROCESS_CPU_HIGH);
        assertThat(result.summary()).isEqualTo("legacy result");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), AgentType.JVM_AGENT,
                "order-service", Instant.now().plusSeconds(10));
    }
}
