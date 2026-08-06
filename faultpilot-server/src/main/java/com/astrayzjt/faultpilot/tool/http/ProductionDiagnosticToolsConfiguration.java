package com.astrayzjt.faultpilot.tool.http;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.observability.ActuatorClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient.Sample;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "faultpilot.integration", name = "mode", havingValue = "PRODUCTION_READ_ONLY")
public class ProductionDiagnosticToolsConfiguration {

    @Bean
    PrometheusClient prometheusClient(org.springframework.web.client.RestClient.Builder builder,
                                      ObservabilityProperties properties,
                                      ServiceCatalogProperties catalog) {
        return new PrometheusClient(builder, properties, catalog);
    }

    @Bean
    ActuatorClient actuatorClient(ObservabilityProperties properties,
                                  ServiceCatalogProperties catalog) {
        return new ActuatorClient(properties, catalog);
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusProcessCpu(PrometheusClient client,
                                                                    ObservabilityProperties properties) {
        return metricTool("query_prometheus_process_cpu", AgentType.JVM_AGENT,
                (service, ignored) -> {
                    List<Sample> samples = client.queryMetric("process_cpu_usage", service);
                    if (samples.isEmpty()) {
                        return ToolResult.failure(source(service, "process_cpu_usage"),
                                "Prometheus process CPU metric is unavailable");
                    }
                    double value = samples.get(samples.size() - 1).value();
                    boolean high = value >= properties.getProcessCpuHighThreshold();
                    return new ToolResult(true,
                            high ? "Process CPU usage is above the configured threshold" : "Process CPU usage is within normal range",
                            Map.of("metric", "process_cpu_usage", "value", value,
                                    "threshold", properties.getProcessCpuHighThreshold()),
                            high ? EvidenceType.PROCESS_CPU_HIGH : EvidenceType.PROCESS_CPU_NORMAL,
                            source(service, "process_cpu_usage"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusJvmMemory(PrometheusClient client,
                                                                  ObservabilityProperties properties) {
        return metricTool("query_prometheus_jvm_memory", AgentType.JVM_AGENT,
                (service, ignored) -> {
                    List<Sample> usedSamples = client.queryMetric("jvm_memory_used_bytes", service, ",area=\"heap\"");
                    List<Sample> maxSamples = client.queryMetric("jvm_memory_max_bytes", service, ",area=\"heap\"");
                    double used = usedSamples.stream().mapToDouble(Sample::value).sum();
                    double max = maxSamples.stream().mapToDouble(Sample::value).filter(value -> value > 0).sum();
                    if (usedSamples.isEmpty() || max <= 0) {
                        return ToolResult.failure(source(service, "jvm_memory"),
                                "Prometheus JVM heap memory metrics are unavailable");
                    }
                    double ratio = used / max;
                    boolean high = ratio >= properties.getHeapUsageHighRatio();
                    return new ToolResult(true,
                            high ? "JVM heap usage is above the configured observation threshold" : "JVM heap usage is within the configured range",
                            Map.of("usedBytes", used, "maxBytes", max, "usageRatio", ratio,
                                    "threshold", properties.getHeapUsageHighRatio()),
                            null, source(service, "jvm_memory"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusThreadPool(PrometheusClient client,
                                                                    ObservabilityProperties properties) {
        return metricTool("query_prometheus_thread_pool", AgentType.JVM_AGENT,
                (service, ignored) -> {
                    List<Sample> activeSamples = client.queryMetric("executor_active_threads", service);
                    List<Sample> sizeSamples = client.queryMetric("executor_pool_size_threads", service);
                    List<Sample> queuedSamples = client.queryMetric("executor_queued_tasks", service);
                    if (activeSamples.isEmpty() || sizeSamples.isEmpty()) {
                        return ToolResult.failure(source(service, "executor"),
                                "Prometheus executor metrics are unavailable");
                    }
                    double active = activeSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double size = sizeSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double queued = queuedSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean saturated = size > 0 && active / size >= properties.getThreadPoolSaturationRatio();
                    return new ToolResult(true,
                            saturated ? "Executor active threads are near pool capacity" : "Executor metrics are within normal range",
                            Map.of("activeThreads", active, "poolSize", size, "queuedTasks", queued,
                                    "saturationRatio", size == 0 ? 0 : active / size),
                            saturated ? EvidenceType.THREAD_POOL_ACTIVE_AT_MAX : EvidenceType.THREAD_POOL_NORMAL,
                            source(service, "executor"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusHikariPool(PrometheusClient client) {
        return metricTool("query_prometheus_hikari_pool", AgentType.DATABASE_AGENT,
                (service, ignored) -> {
                    List<Sample> activeSamples = client.queryMetric("hikaricp_connections_active", service);
                    List<Sample> maxSamples = client.queryMetric("hikaricp_connections_max", service);
                    List<Sample> pendingSamples = client.queryMetric("hikaricp_connections_pending", service);
                    if (activeSamples.isEmpty() || maxSamples.isEmpty() || pendingSamples.isEmpty()) {
                        return ToolResult.failure(source(service, "hikaricp"),
                                "Prometheus Hikari connection pool metrics are unavailable");
                    }
                    double active = activeSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double max = maxSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double pending = pendingSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean exhausted = pending > 0 || (max > 0 && active >= max);
                    return new ToolResult(true,
                            exhausted ? "Hikari connection pool is saturated" : "Hikari connection pool is within normal range",
                            Map.of("activeConnections", active, "maxConnections", max, "pendingThreads", pending),
                            exhausted ? EvidenceType.DB_POOL_ACTIVE_AT_MAX : null,
                            source(service, "hikaricp"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusHttpLatency(PrometheusClient client,
                                                                    ObservabilityProperties properties) {
        return metricTool("query_prometheus_http_latency", AgentType.DATABASE_AGENT,
                (service, ignored) -> {
                    List<Sample> samples = client.queryMetric("http_server_requests_seconds_max", service,
                            ",uri!~\"/actuator.*\"");
                    if (samples.isEmpty()) {
                        return ToolResult.failure(source(service, "http_server_requests_seconds_max"),
                                "Prometheus HTTP latency metric is unavailable");
                    }
                    double value = samples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean high = value >= properties.getHttpLatencyHighSeconds();
                    return new ToolResult(true,
                            high ? "Observed HTTP request latency is above the configured threshold" : "Observed HTTP latency is within normal range",
                            Map.of("maxSeconds", value, "threshold", properties.getHttpLatencyHighSeconds()),
                            high ? EvidenceType.API_LATENCY_REGRESSION : null,
                            source(service, "http_server_requests_seconds_max"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryDownstreamPrometheusLatency(PrometheusClient client,
                                                                          ServiceCatalogProperties catalog,
                                                                          ObservabilityProperties properties) {
        return metricTool("query_prometheus_downstream_latency", AgentType.DEPENDENCY_AGENT,
                (service, ignored) -> {
                    List<String> downstreams = catalog.require(service).downstreams();
                    if (downstreams.isEmpty()) {
                        return ToolResult.failure(source(service, "downstream"), "No downstream service is configured");
                    }
                    String downstream = downstreams.get(0);
                    List<Sample> samples = client.queryMetric("http_server_requests_seconds_max", downstream,
                            ",uri!~\"/actuator.*\"");
                    if (samples.isEmpty()) {
                        return ToolResult.failure(source(downstream, "http_server_requests_seconds_max"),
                                "Prometheus downstream latency metric is unavailable");
                    }
                    double value = samples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean high = value >= properties.getHttpLatencyHighSeconds();
                    return new ToolResult(true,
                            high ? "Downstream request latency is above the configured threshold" : "Downstream latency is within normal range",
                            Map.of("downstream", downstream, "maxSeconds", value,
                                    "threshold", properties.getHttpLatencyHighSeconds()),
                            high ? EvidenceType.DOWNSTREAM_LATENCY_HIGH : null,
                            source(downstream, "http_server_requests_seconds_max"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryActuatorHealth(ActuatorClient client) {
        return actuatorTool("query_actuator_health", (service, ignored) -> {
            Map<String, Object> data = client.health(service);
            boolean up = "UP".equalsIgnoreCase(String.valueOf(data.getOrDefault("status", "UNKNOWN")));
            return new ToolResult(up, up ? "Actuator health is UP" : "Actuator health is not UP", data,
                    up ? null : EvidenceType.DATA_UNAVAILABLE, "actuator:" + service + ":health");
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryActuatorJvmThreads(ActuatorClient client) {
        return actuatorTool("query_actuator_jvm_threads", (service, ignored) -> {
            Map<String, Object> data = client.metric(service, "jvm.threads.live");
            return new ToolResult(true, "Actuator JVM live-thread metric collected", data, null,
                    "actuator:" + service + ":jvm.threads.live");
        });
    }

    private DiagnosticTool<Map<String, Object>> metricTool(String name, AgentType owner, Probe probe) {
        return tool(name, owner, probe);
    }

    private DiagnosticTool<Map<String, Object>> actuatorTool(String name, Probe probe) {
        return tool(name, AgentType.JVM_AGENT, probe);
    }

    private DiagnosticTool<Map<String, Object>> tool(String name, AgentType owner, Probe probe) {
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
                try {
                    return probe.run(context.serviceName(), arguments);
                } catch (RuntimeException exception) {
                    return ToolResult.failure("observability:" + context.serviceName(),
                            "Observability source is unavailable");
                }
            }
        };
    }

    private static String source(String service, String metric) {
        return "prometheus:" + service + ":" + metric;
    }

    @FunctionalInterface
    private interface Probe {
        ToolResult run(String serviceName, Map<String, Object> arguments);
    }
}
