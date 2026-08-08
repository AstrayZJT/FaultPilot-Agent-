package com.astrayzjt.faultpilot.triage;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaselineCollectorTest {

    @Test
    void collectsThreadPoolSignalAlongsideCpuForProductionReadOnlyRouting() {
        ToolRegistry registry = mock(ToolRegistry.class);
        EvidenceService evidenceService = mock(EvidenceService.class);
        TraceRepository traceRepository = mock(TraceRepository.class);
        ToolResult cpuResult = new ToolResult(true, "CPU is high", Map.of(), EvidenceType.PROCESS_CPU_HIGH,
                "prometheus:order-service:process_cpu_usage");
        ToolResult threadPoolResult = new ToolResult(true, "Thread pool is saturated", Map.of(),
                EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, "prometheus:order-service:executor");
        DiagnosticTool<Map<String, Object>> cpu = tool("query_prometheus_process_cpu", cpuResult);
        DiagnosticTool<Map<String, Object>> threadPool = tool("query_prometheus_thread_pool", threadPoolResult);

        when(registry.names(AgentType.JVM_AGENT)).thenReturn(
                List.of("query_prometheus_process_cpu", "query_prometheus_thread_pool"));
        doReturn(cpu).when(registry).require("query_prometheus_process_cpu", AgentType.JVM_AGENT);
        doReturn(threadPool).when(registry).require("query_prometheus_thread_pool", AgentType.JVM_AGENT);
        when(evidenceService.record(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            ToolResult result = invocation.getArgument(2);
            return evidence(result.evidenceType(), result.source(), invocation.getArgument(0));
        });

        var collector = new BaselineCollector(registry, evidenceService, traceRepository);
        List<Evidence> evidence = collector.collect(incident());

        assertThat(evidence).extracting(Evidence::type)
                .containsExactly(EvidenceType.PROCESS_CPU_HIGH, EvidenceType.THREAD_POOL_ACTIVE_AT_MAX);
        verify(registry).require("query_prometheus_thread_pool", AgentType.JVM_AGENT);
    }

    @SuppressWarnings("unchecked")
    private DiagnosticTool<Map<String, Object>> tool(String name, ToolResult result) {
        DiagnosticTool<Map<String, Object>> tool = mock(DiagnosticTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.owner()).thenReturn(AgentType.JVM_AGENT);
        when(tool.risk()).thenReturn(ToolRisk.READ_ONLY);
        when(tool.argumentType()).thenReturn((Class<Map<String, Object>>) (Class<?>) Map.class);
        when(tool.execute(any(), any())).thenReturn(result);
        return tool;
    }

    private Incident incident() {
        UUID id = UUID.randomUUID();
        Instant end = Instant.now();
        IncidentSnapshot snapshot = new IncidentSnapshot(id, "order-service", "something is wrong", null,
                new TimeRange(end.minusSeconds(60), end), null, null, null, false, end);
        return new Incident(id, IncidentStatus.ACCEPTED, snapshot, end, end);
    }

    private Evidence evidence(EvidenceType type, String source, UUID incidentId) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), incidentId, null, type, source, source,
                now.minusSeconds(60), now, type.name(), null, type.name(), now);
    }
}
