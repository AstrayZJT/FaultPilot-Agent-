package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentTaskStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.FindingStatus;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentStepRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecialistAgentRunnerTest {

    @Test
    void normalizesCommonModelAliasesWithoutWeakeningEvidenceIds() {
        Fixture fixture = fixture();
        String decision = "{\"action\":\"COMPLETE\",\"toolName\":null,\"arguments\":{}," +
                "\"evidenceIds\":[],\"suggestedAgent\":null,\"decisionSummary\":\"done\"}";
        String finding = "{\"status\":\"CONFIRMED\",\"causeCode\":\"CPU_HOTSPOT\"," +
                "\"supportingEvidenceIds\":[\"" + fixture.evidence.evidenceId() + "\",\"" + UUID.randomUUID() + "\"]," +
                "\"counterEvidenceIds\":[],\"completedChecks\":[\"PROCESS_CPU_HIGH\"]," +
                "\"missingChecks\":[],\"suggestedAgent\":\"NONE\",\"summary\":\"CPU evidence is sufficient\"}";
        when(fixture.modelClient.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(decision, finding);

        AgentFinding result = fixture.runner.run(fixture.task, fixture.snapshot, List.of(fixture.evidence));

        assertThat(result.status()).isEqualTo(FindingStatus.SUCCEEDED);
        assertThat(result.causeCode()).isEqualTo(CauseCode.JVM_CPU_HOTSPOT);
        assertThat(result.supportingEvidenceIds()).containsExactly(fixture.evidence.evidenceId());
        assertThat(result.suggestedAgent()).isNull();
        assertThat(result.stepsUsed()).isEqualTo(1);
    }

    @Test
    void preservesEvidenceWhenBothFindingResponsesAreMalformed() {
        Fixture fixture = fixture();
        String decision = "{\"action\":\"COMPLETE\",\"arguments\":{},\"evidenceIds\":[]," +
                "\"suggestedAgent\":null,\"decisionSummary\":\"done\"}";
        when(fixture.modelClient.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(decision, "not-json", "still-not-json");

        AgentFinding result = fixture.runner.run(fixture.task, fixture.snapshot, List.of(fixture.evidence));

        assertThat(result.status()).isEqualTo(FindingStatus.INSUFFICIENT_EVIDENCE);
        assertThat(result.causeCode()).isEqualTo(CauseCode.UNKNOWN);
        assertThat(result.completedChecks()).contains(EvidenceType.PROCESS_CPU_HIGH);
        verify(fixture.eventService).append(eq(fixture.task.incidentId()), eq("SPECIALIST_OUTPUT_FALLBACK"), any());
    }

    private Fixture fixture() {
        UUID incidentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        AgentTask task = new AgentTask(taskId, incidentId, "jvm-round-1", AgentType.JVM_AGENT,
                "Investigate CPU", 1, 1, AgentTaskStatus.PENDING, null, null);
        IncidentSnapshot snapshot = new IncidentSnapshot(incidentId, "order-service", "CPU high", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        Evidence evidence = new Evidence(UUID.randomUUID(), incidentId, null, EvidenceType.PROCESS_CPU_HIGH,
                "prometheus:order-service:process_cpu_usage", "order-service", now.minusSeconds(60), now,
                "Process CPU is high", null, "hash", now);

        RemoteModelClient modelClient = mock(RemoteModelClient.class);
        AgentStepRepository stepRepository = mock(AgentStepRepository.class);
        when(stepRepository.recordDecision(any(), anyInt(), any(), anyString())).thenReturn(UUID.randomUUID());
        IncidentEventService eventService = mock(IncidentEventService.class);
        SpecialistAgentRunner runner = new SpecialistAgentRunner(new ToolRegistry(List.of()),
                mock(EvidenceService.class), new ObjectMapper(), modelClient, mock(ToolInvocationGuard.class),
                stepRepository, mock(TraceRepository.class), eventService);
        return new Fixture(runner, modelClient, eventService, task, snapshot, evidence);
    }

    private record Fixture(SpecialistAgentRunner runner, RemoteModelClient modelClient,
                           IncidentEventService eventService, AgentTask task,
                           IncidentSnapshot snapshot, Evidence evidence) {
    }
}
