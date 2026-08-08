package com.astrayzjt.faultpilot.triage;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class BaselineCollector {

    private static final List<BaselineProbe> PROBES = List.of(
            new BaselineProbe(AgentType.JVM_AGENT,
                    List.of("query_prometheus_process_cpu", "query_prometheus_thread_pool", "query_jvm_overview")),
            new BaselineProbe(AgentType.DATABASE_AGENT,
                    List.of("query_prometheus_hikari_pool", "query_database_overview")),
            new BaselineProbe(AgentType.DEPENDENCY_AGENT,
                    List.of("query_prometheus_downstream_latency", "query_downstream_health")),
            new BaselineProbe(AgentType.CACHE_AGENT,
                    List.of("query_prometheus_redis_client_pool", "query_cache_overview")));

    private final ToolRegistry toolRegistry;
    private final EvidenceService evidenceService;
    private final TraceRepository traceRepository;

    public BaselineCollector(ToolRegistry toolRegistry, EvidenceService evidenceService, TraceRepository traceRepository) {
        this.toolRegistry = toolRegistry;
        this.evidenceService = evidenceService;
        this.traceRepository = traceRepository;
    }

    public List<Evidence> collect(Incident incident) {
        Instant deadline = Instant.now().plusSeconds(8);
        List<Evidence> recorded = new ArrayList<>();
        for (BaselineProbe probe : PROBES) {
            List<String> availableTools = probe.toolNames().stream()
                    .filter(candidate -> toolRegistry.names(probe.owner()).contains(candidate))
                    .distinct()
                    .toList();
            for (String name : availableTools) {
                ToolResult result = execute(probe.owner(), name, incident, deadline);
                Evidence evidence = evidenceService.record(incident.incidentId(), null, result,
                        incident.snapshot().timeRange().start(), incident.snapshot().timeRange().end());
                if (evidence != null) {
                    recorded.add(evidence);
                }
            }
        }
        return List.copyOf(recorded);
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(AgentType owner, String name, Incident incident, Instant deadline) {
        DiagnosticTool<Map<String, Object>> tool = (DiagnosticTool<Map<String, Object>>) toolRegistry.require(name, owner);
        Instant startedAt = Instant.now();
        try {
            ToolResult result = tool.execute(Map.of(), new ToolExecutionContext(incident.incidentId(), null, owner,
                    incident.snapshot().serviceName(), deadline));
            traceRepository.tool(incident.incidentId(), null, "BASELINE_" + owner.name(), name, "baseline",
                    result.success() ? "SUCCEEDED" : "FAILED", result.summary(), null, startedAt, Instant.now());
            return result;
        } catch (RuntimeException exception) {
            traceRepository.tool(incident.incidentId(), null, "BASELINE_" + owner.name(), name, "baseline",
                    "FAILED", null, exception.getClass().getSimpleName(), startedAt, Instant.now());
            return ToolResult.failure("baseline:" + incident.snapshot().serviceName() + ":" + name,
                    "Baseline probe is unavailable");
        }
    }

    private record BaselineProbe(AgentType owner, List<String> toolNames) {
    }
}
