package com.astrayzjt.faultpilot.tool.http;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Configuration
public class LabDiagnosticToolsConfiguration {

    @Bean
    DiagnosticTool<Map<String, Object>> queryJvmOverview(ServiceCatalogProperties catalog) {
        return tool("query_jvm_overview", AgentType.JVM_AGENT, "/api/orders/internal/diagnostics", catalog,
                (data, source) -> {
                    if (Boolean.TRUE.equals(data.get("cpuHotspot"))) {
                        return new ToolResult(true, "Order process CPU hotspot is active", data,
                                EvidenceType.PROCESS_CPU_HIGH, source);
                    }
                    if (Boolean.TRUE.equals(data.get("threadPoolExhausted"))) {
                        return new ToolResult(true, "Order worker pool is saturated", data,
                                EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, source);
                    }
                    return new ToolResult(true, "JVM lab overview is within normal fault signals", data,
                            EvidenceType.PROCESS_CPU_NORMAL, source);
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryDatabaseOverview(ServiceCatalogProperties catalog) {
        return tool("query_database_overview", AgentType.DATABASE_AGENT, "/api/orders/internal/diagnostics", catalog,
                (data, source) -> {
                    if (Boolean.TRUE.equals(data.get("slowSql"))) {
                        return new ToolResult(true, "Slow SQL scenario is active", data,
                                EvidenceType.SLOW_SQL_FOUND, source);
                    }
                    if (Boolean.TRUE.equals(data.get("dbPoolExhausted"))) {
                        return new ToolResult(true, "Database pool pressure scenario is active", data,
                                EvidenceType.DB_POOL_ACTIVE_AT_MAX, source);
                    }
                    return new ToolResult(true, "Database lab overview is normal", data,
                            EvidenceType.THREAD_POOL_NORMAL, source);
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryDownstreamHealth(ServiceCatalogProperties catalog) {
        return tool("query_downstream_health", AgentType.DEPENDENCY_AGENT, "/api/inventory/internal/diagnostics", catalog,
                (data, source) -> new ToolResult(true,
                        Boolean.TRUE.equals(data.get("dependencyDelay")) ? "Downstream delay is active" : "Downstream is healthy",
                        data, Boolean.TRUE.equals(data.get("dependencyDelay")) ? EvidenceType.DOWNSTREAM_LATENCY_HIGH : null,
                        source));
    }

    private DiagnosticTool<Map<String, Object>> tool(
            String name,
            AgentType owner,
            String path,
            ServiceCatalogProperties catalog,
            ResultMapper mapper) {
        return new DiagnosticTool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentType owner() {
                return owner;
            }

            @Override
            public ToolRisk risk() {
                return ToolRisk.READ_ONLY;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Class<Map<String, Object>> argumentType() {
                return (Class<Map<String, Object>>) (Class<?>) Map.class;
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
                context.throwIfExpired();
                String service = owner == AgentType.DEPENDENCY_AGENT ? "inventory-service" : "order-service";
                String baseUrl = catalog.require(service).actuatorBaseUrl();
                try {
                    Map<String, Object> data = RestClient.create(baseUrl).get().uri(path).retrieve().body(Map.class);
                    return mapper.map(data == null ? Map.of() : data, service + path);
                } catch (RuntimeException exception) {
                    return ToolResult.failure(service + path, "Diagnostic endpoint unavailable");
                }
            }
        };
    }

    @FunctionalInterface
    private interface ResultMapper {
        ToolResult map(Map<String, Object> data, String source);
    }
}
