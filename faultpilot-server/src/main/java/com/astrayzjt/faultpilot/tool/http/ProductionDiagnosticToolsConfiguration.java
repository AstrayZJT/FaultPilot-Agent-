package com.astrayzjt.faultpilot.tool.http;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.incident.config.DatabaseCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.RedisCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.incident.config.TraceCatalogProperties;
import com.astrayzjt.faultpilot.observability.ActuatorClient;
import com.astrayzjt.faultpilot.observability.ArthasClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient;
import com.astrayzjt.faultpilot.observability.PrometheusClient.Sample;
import com.astrayzjt.faultpilot.observability.PostgresDiagnosticsClient;
import com.astrayzjt.faultpilot.observability.JaegerTraceDiagnosticsClient;
import com.astrayzjt.faultpilot.observability.RedisDiagnosticsClient;
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
    ArthasClient arthasClient(ObservabilityProperties properties,
                              ServiceCatalogProperties catalog,
                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ArthasClient(properties, catalog, objectMapper);
    }

    @Bean
    RedisDiagnosticsClient redisDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                                  RedisCatalogProperties redisCatalog,
                                                  ObservabilityProperties properties) {
        return new RedisDiagnosticsClient(serviceCatalog, redisCatalog, properties);
    }

    @Bean
    PostgresDiagnosticsClient postgresDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                                        DatabaseCatalogProperties databaseCatalog) {
        return new PostgresDiagnosticsClient(serviceCatalog, databaseCatalog);
    }

    @Bean
    JaegerTraceDiagnosticsClient jaegerTraceDiagnosticsClient(ServiceCatalogProperties serviceCatalog,
                                                              TraceCatalogProperties traceCatalog,
                                                              ObservabilityProperties observabilityProperties,
                                                              com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new JaegerTraceDiagnosticsClient(serviceCatalog, traceCatalog, observabilityProperties, objectMapper);
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
    DiagnosticTool<Map<String, Object>> queryArthasWaitingThreads(ArthasClient client) {
        return tool("query_arthas_waiting_threads", AgentType.JVM_AGENT, (service, ignored) -> {
            ArthasClient.ThreadInspection inspection = client.inspectWaitingThreads(service);
            String source = "arthas:" + service + ":waiting-threads";
            if (!inspection.configured()) {
                return new ToolResult(true, "Arthas thread inspection is not configured for this service",
                        Map.of("configured", false), null, source);
            }
            if (!inspection.codePackagePrefixesConfigured()) {
                return new ToolResult(true, "Arthas is configured but no application code package prefixes are configured",
                        Map.of("configured", true, "codePackagePrefixesConfigured", false), null, source);
            }
            if (!inspection.available()) {
                return ToolResult.failure(source, "Arthas waiting-thread inspection is unavailable");
            }
            Map<String, Object> data = Map.of(
                    "waitingThreadCount", inspection.waitingThreadCount(),
                    "blockingThreads", inspection.blockingThreads().stream()
                            .map(ArthasClient.BlockingThread::asEvidenceData)
                            .toList());
            if (inspection.blockingThreads().isEmpty()) {
                return new ToolResult(true, "Arthas found no WAITING threads in configured application packages", data,
                        null, source);
            }
            ArthasClient.BlockingThread first = inspection.blockingThreads().getFirst();
            return new ToolResult(true,
                    "Arthas found " + inspection.blockingThreads().size() + " WAITING application thread(s); first application location: "
                            + first.sourceLocation() + "; blocking operation: " + first.blockingOperation(),
                    data, EvidenceType.BLOCKING_TASK_FOUND, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryArthasHotThreads(ArthasClient client) {
        return tool("query_arthas_hot_threads", AgentType.JVM_AGENT, (service, ignored) -> {
            ArthasClient.HotThreadInspection inspection = client.inspectHotThreads(service);
            String source = "arthas:" + service + ":hot-threads";
            if (!inspection.configured()) {
                return new ToolResult(true, "Arthas hot-thread inspection is not configured for this service",
                        Map.of("configured", false), null, source);
            }
            if (!inspection.codePackagePrefixesConfigured()) {
                return new ToolResult(true, "Arthas is configured but no application code package prefixes are configured",
                        Map.of("configured", true, "codePackagePrefixesConfigured", false), null, source);
            }
            if (!inspection.available()) {
                return ToolResult.failure(source, "Arthas hot-thread inspection is unavailable");
            }
            Map<String, Object> data = Map.of("hotThreads", inspection.hotThreads().stream()
                    .map(ArthasClient.HotThread::asEvidenceData).toList());
            if (inspection.hotThreads().isEmpty()) {
                return new ToolResult(true, "Arthas found no hot application thread in the bounded sample", data, null, source);
            }
            ArthasClient.HotThread first = inspection.hotThreads().getFirst();
            return new ToolResult(true, "Arthas identified " + inspection.hotThreads().size()
                    + " hot application thread(s); first application location: " + first.sourceLocation(),
                    data, EvidenceType.CPU_HOT_METHOD_FOUND, source);
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
    DiagnosticTool<Map<String, Object>> inspectPostgresSlowStatements(PostgresDiagnosticsClient client,
                                                                        ObservabilityProperties properties) {
        return tool("inspect_postgres_slow_statements", AgentType.DATABASE_AGENT, (service, ignored) -> {
            PostgresDiagnosticsClient.SlowStatementInspection inspection = client.inspectSlowStatements(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No PostgreSQL diagnostic target is configured for this service",
                        Map.of("configured", false), null, "postgres:" + service + ":pg_stat_statements");
            }
            String source = "postgres:" + inspection.databaseReference() + ":pg_stat_statements";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured PostgreSQL pg_stat_statements probe is unavailable");
            }
            List<PostgresDiagnosticsClient.SlowStatement> slowStatements = inspection.statements().stream()
                    .filter(statement -> statement.meanDurationMillis() >= properties.getDatabaseSlowQueryThresholdMillis()
                            || statement.maxDurationMillis() >= properties.getDatabaseSlowQueryThresholdMillis())
                    .toList();
            return new ToolResult(true,
                    slowStatements.isEmpty() ? "PostgreSQL statement fingerprints are below the configured slow-query threshold" :
                            "PostgreSQL pg_stat_statements contains " + slowStatements.size() + " slow statement fingerprint(s)",
                    Map.of("databaseRef", inspection.databaseReference(),
                            "thresholdMillis", properties.getDatabaseSlowQueryThresholdMillis(),
                            "statements", slowStatements.stream().map(PostgresDiagnosticsClient.SlowStatement::asEvidenceData).toList()),
                    slowStatements.isEmpty() ? null : EvidenceType.SLOW_SQL_FOUND, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectPostgresConnectionHolders(PostgresDiagnosticsClient client,
                                                                           ObservabilityProperties properties) {
        return tool("inspect_postgres_connection_holders", AgentType.DATABASE_AGENT, (service, ignored) -> {
            PostgresDiagnosticsClient.ConnectionHolderInspection inspection = client.inspectConnectionHolders(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No PostgreSQL diagnostic target is configured for this service",
                        Map.of("configured", false), null, "postgres:" + service + ":pg_stat_activity");
            }
            String source = "postgres:" + inspection.databaseReference() + ":pg_stat_activity";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured PostgreSQL pg_stat_activity probe is unavailable");
            }
            List<PostgresDiagnosticsClient.ConnectionHolder> holdingConnections = inspection.holders().stream()
                    .filter(holder -> holder.longestAgeMillis() >= properties.getDatabaseHoldingQueryThresholdMillis())
                    .toList();
            return new ToolResult(true,
                    holdingConnections.isEmpty() ? "PostgreSQL reports no non-idle connection above the configured holding threshold" :
                            "PostgreSQL found " + holdingConnections.size() + " bounded connection-holder group(s)",
                    Map.of("databaseRef", inspection.databaseReference(),
                            "thresholdMillis", properties.getDatabaseHoldingQueryThresholdMillis(),
                            "holders", holdingConnections.stream().map(PostgresDiagnosticsClient.ConnectionHolder::asEvidenceData).toList()),
                    holdingConnections.isEmpty() ? null : EvidenceType.CONNECTION_HOLDING_QUERY_FOUND, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectTraceSlowDatabaseSpans(JaegerTraceDiagnosticsClient client,
                                                                       ObservabilityProperties properties) {
        return tool("inspect_trace_slow_database_spans", AgentType.DATABASE_AGENT, (service, ignored) -> {
            JaegerTraceDiagnosticsClient.TraceInspection inspection = client.inspectSlowDatabaseSpans(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Jaeger trace backend is configured for this service", Map.of("configured", false),
                        null, "jaeger:" + service + ":postgresql-spans");
            }
            String source = "jaeger:" + inspection.traceReference() + ":postgresql-spans";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured Jaeger PostgreSQL span probe is unavailable");
            }
            List<JaegerTraceDiagnosticsClient.TraceSpan> spans = inspection.spans().stream()
                    .filter(span -> span.durationMillis() >= properties.getTraceSlowSpanThresholdMillis())
                    .toList();
            return new ToolResult(true,
                    spans.isEmpty() ? "Jaeger found no PostgreSQL span above the configured duration threshold" :
                            "Jaeger found " + spans.size() + " slow PostgreSQL span summary record(s) in the service trace window",
                    Map.of("traceRef", inspection.traceReference(), "thresholdMillis", properties.getTraceSlowSpanThresholdMillis(),
                            "spans", spans.stream().map(JaegerTraceDiagnosticsClient.TraceSpan::asEvidenceData).toList()),
                    spans.isEmpty() ? null : EvidenceType.API_AND_SQL_TIME_CORRELATED, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectTraceSlowDependencySpans(JaegerTraceDiagnosticsClient client,
                                                                         ObservabilityProperties properties) {
        return tool("inspect_trace_slow_dependency_spans", AgentType.DEPENDENCY_AGENT, (service, ignored) -> {
            JaegerTraceDiagnosticsClient.TraceInspection inspection = client.inspectSlowDependencySpans(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Jaeger trace backend is configured for this service", Map.of("configured", false),
                        null, "jaeger:" + service + ":dependency-spans");
            }
            String source = "jaeger:" + inspection.traceReference() + ":dependency-spans";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured Jaeger downstream span probe is unavailable");
            }
            List<JaegerTraceDiagnosticsClient.TraceSpan> spans = inspection.spans().stream()
                    .filter(span -> span.durationMillis() >= properties.getTraceSlowSpanThresholdMillis())
                    .toList();
            return new ToolResult(true,
                    spans.isEmpty() ? "Jaeger found no downstream client span above the configured duration threshold" :
                            "Jaeger found " + spans.size() + " slow downstream client span summary record(s) in the service trace window",
                    Map.of("traceRef", inspection.traceReference(), "thresholdMillis", properties.getTraceSlowSpanThresholdMillis(),
                            "spans", spans.stream().map(JaegerTraceDiagnosticsClient.TraceSpan::asEvidenceData).toList()),
                    spans.isEmpty() ? null : EvidenceType.SLOW_CHILD_SPAN_FOUND, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectTraceRedisSpans(JaegerTraceDiagnosticsClient client,
                                                                ObservabilityProperties properties) {
        return tool("inspect_trace_redis_spans", AgentType.CACHE_AGENT, (service, ignored) -> {
            JaegerTraceDiagnosticsClient.TraceInspection inspection = client.inspectRedisSpans(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Jaeger trace backend is configured for this service", Map.of("configured", false),
                        null, "jaeger:" + service + ":redis-spans");
            }
            String source = "jaeger:" + inspection.traceReference() + ":redis-spans";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured Jaeger Redis span probe is unavailable");
            }
            List<JaegerTraceDiagnosticsClient.TraceSpan> spans = inspection.spans().stream()
                    .filter(span -> span.durationMillis() >= properties.getTraceSlowSpanThresholdMillis())
                    .toList();
            return new ToolResult(true,
                    spans.isEmpty() ? "Jaeger found no Redis span above the configured duration threshold" :
                            "Jaeger found " + spans.size() + " slow Redis span summary record(s) in the service trace window",
                    Map.of("traceRef", inspection.traceReference(), "thresholdMillis", properties.getTraceSlowSpanThresholdMillis(),
                            "spans", spans.stream().map(JaegerTraceDiagnosticsClient.TraceSpan::asEvidenceData).toList()),
                    spans.isEmpty() ? null : EvidenceType.REDIS_TRACE_LATENCY_CORRELATED, source);
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
    DiagnosticTool<Map<String, Object>> queryPrometheusRedisCommandLatency(PrometheusClient client,
                                                                             ObservabilityProperties properties) {
        return metricTool("query_prometheus_redis_command_latency", AgentType.CACHE_AGENT,
                (service, ignored) -> {
                    String metric = "faultpilot_redis_command_latency_seconds_max";
                    List<Sample> samples = client.queryMetric(metric, service);
                    if (samples.isEmpty()) {
                        return ToolResult.failure(source(service, metric),
                                "Prometheus Redis command latency metric is unavailable");
                    }
                    double value = samples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean high = value >= properties.getRedisCommandLatencyHighSeconds();
                    return new ToolResult(true,
                            high ? "Redis command latency is above the configured threshold" :
                                    "Redis command latency is within the configured range",
                            Map.of("maxSeconds", value, "threshold", properties.getRedisCommandLatencyHighSeconds()),
                            high ? EvidenceType.REDIS_COMMAND_LATENCY_HIGH : EvidenceType.REDIS_COMMAND_LATENCY_NORMAL,
                            source(service, metric));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> queryPrometheusRedisClientPool(PrometheusClient client) {
        return metricTool("query_prometheus_redis_client_pool", AgentType.CACHE_AGENT,
                (service, ignored) -> {
                    List<Sample> activeSamples = client.queryMetric("faultpilot_redis_client_pool_active", service);
                    List<Sample> maxSamples = client.queryMetric("faultpilot_redis_client_pool_max", service);
                    List<Sample> pendingSamples = client.queryMetric("faultpilot_redis_client_pool_pending", service);
                    if (activeSamples.isEmpty() || maxSamples.isEmpty() || pendingSamples.isEmpty()) {
                        return ToolResult.failure(source(service, "faultpilot_redis_client_pool"),
                                "Prometheus Redis client pool metrics are unavailable");
                    }
                    double active = activeSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double max = maxSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    double pending = pendingSamples.stream().mapToDouble(Sample::value).max().orElse(0);
                    boolean exhausted = pending > 0 || (max > 0 && active >= max);
                    return new ToolResult(true,
                            exhausted ? "Redis client pool has waiting or saturated callers" :
                                    "Redis client pool is within normal range",
                            Map.of("activeClients", active, "maxClients", max, "pendingClients", pending),
                            exhausted ? EvidenceType.REDIS_CLIENT_POOL_PENDING_HIGH : EvidenceType.REDIS_CLIENT_POOL_NORMAL,
                            source(service, "faultpilot_redis_client_pool"));
                });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectRedisServerInfo(RedisDiagnosticsClient client,
                                                                 ObservabilityProperties properties) {
        return tool("inspect_redis_server_info", AgentType.CACHE_AGENT, (service, ignored) -> {
            RedisDiagnosticsClient.ServerInspection inspection = client.inspectServer(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Redis instance is configured for this service", Map.of("configured", false),
                        null, "redis:" + service + ":info");
            }
            if (!inspection.available()) {
                return ToolResult.failure("redis:" + inspection.redisReference() + ":info",
                        "Configured Redis read-only INFO probe is unavailable");
            }
            long usedMemory = inspection.values().getOrDefault("used_memory", 0L);
            long maxMemory = inspection.values().getOrDefault("maxmemory", 0L);
            long evictedKeys = inspection.values().getOrDefault("evicted_keys", 0L);
            long connectedClients = inspection.values().getOrDefault("connected_clients", 0L);
            long blockedClients = inspection.values().getOrDefault("blocked_clients", 0L);
            boolean memoryPressure = maxMemory > 0 && (double) usedMemory / maxMemory >= properties.getRedisMemoryUsageHighRatio();
            boolean evictions = evictedKeys >= properties.getRedisEvictionsHighThreshold();
            EvidenceType evidenceType = memoryPressure ? EvidenceType.REDIS_MEMORY_PRESSURE :
                    evictions ? EvidenceType.REDIS_EVICTIONS_HIGH : null;
            String summary = memoryPressure ? "Redis memory use is above the configured observation threshold" :
                    evictions ? "Redis reports evicted keys above the configured observation threshold" :
                            "Redis INFO memory, stats, and clients sections are within configured observation thresholds";
            return new ToolResult(true, summary,
                    Map.of("redisRef", inspection.redisReference(), "usedMemoryBytes", usedMemory,
                            "maxMemoryBytes", maxMemory, "evictedKeys", evictedKeys,
                            "connectedClients", connectedClients, "blockedClients", blockedClients),
                    evidenceType, "redis:" + inspection.redisReference() + ":info");
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectRedisCacheHitRate(RedisDiagnosticsClient client,
                                                                  ObservabilityProperties properties) {
        return tool("inspect_redis_cache_hit_rate", AgentType.CACHE_AGENT, (service, ignored) -> {
            RedisDiagnosticsClient.ServerInspection inspection = client.inspectServer(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Redis instance is configured for this service", Map.of("configured", false),
                        null, "redis:" + service + ":hit-rate");
            }
            String source = "redis:" + inspection.redisReference() + ":hit-rate";
            if (!inspection.available()) {
                return ToolResult.failure(source, "Configured Redis read-only hit-rate probe is unavailable");
            }
            long hits = inspection.values().getOrDefault("keyspace_hits", 0L);
            long misses = inspection.values().getOrDefault("keyspace_misses", 0L);
            long total = Math.max(0, hits) + Math.max(0, misses);
            if (total == 0) {
                return new ToolResult(true, "Redis has not exposed enough keyspace hit/miss activity for a hit-rate decision",
                        Map.of("redisRef", inspection.redisReference(), "hits", hits, "misses", misses), null, source);
            }
            double hitRate = (double) Math.max(0, hits) / total;
            boolean low = hitRate < properties.getRedisCacheHitRateLowRatio();
            return new ToolResult(true,
                    low ? "Redis cache hit rate is below the configured observation threshold" :
                            "Redis cache hit rate is within the configured observation range",
                    Map.of("redisRef", inspection.redisReference(), "hits", hits, "misses", misses,
                            "hitRate", hitRate, "threshold", properties.getRedisCacheHitRateLowRatio()),
                    low ? EvidenceType.REDIS_CACHE_HIT_RATE_LOW : null, source);
        });
    }

    @Bean
    DiagnosticTool<Map<String, Object>> inspectRedisSlowLog(RedisDiagnosticsClient client,
                                                              ObservabilityProperties properties) {
        return tool("inspect_redis_slow_log", AgentType.CACHE_AGENT, (service, ignored) -> {
            RedisDiagnosticsClient.SlowLogInspection inspection = client.readSlowLog(service);
            if (!inspection.configured()) {
                return new ToolResult(true, "No Redis instance is configured for this service", Map.of("configured", false),
                        null, "redis:" + service + ":slowlog");
            }
            if (!inspection.available()) {
                return ToolResult.failure("redis:" + inspection.redisReference() + ":slowlog",
                        "Configured Redis read-only SLOWLOG probe is unavailable");
            }
            List<RedisDiagnosticsClient.SlowLogEntry> slowEntries = inspection.entries().stream()
                    .filter(entry -> entry.durationMicros() >= properties.getRedisSlowCommandThresholdMicros())
                    .toList();
            return new ToolResult(true,
                    slowEntries.isEmpty() ? "Redis SLOWLOG has no command above the configured duration threshold" :
                            "Redis SLOWLOG contains " + slowEntries.size() + " bounded slow command record(s)",
                    Map.of("redisRef", inspection.redisReference(),
                            "thresholdMicros", properties.getRedisSlowCommandThresholdMicros(),
                            "entries", slowEntries.stream().map(RedisDiagnosticsClient.SlowLogEntry::asEvidenceData).toList()),
                    slowEntries.isEmpty() ? null : EvidenceType.REDIS_SLOW_COMMAND_FOUND,
                    "redis:" + inspection.redisReference() + ":slowlog");
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
