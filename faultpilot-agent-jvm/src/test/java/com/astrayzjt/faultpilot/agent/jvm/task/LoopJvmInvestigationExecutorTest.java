package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.DiagnosticObservation;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.EvidenceType;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticCatalog;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticDefinitionLoader;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticToolExecutor;
import com.astrayzjt.faultpilot.agent.jvm.evidence.CentralEvidenceClient;
import com.astrayzjt.faultpilot.agent.jvm.evidence.RemoteEvidenceView;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.JvmReasoningModel;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.SkillDecision;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.StepAction;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.StepDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class LoopJvmInvestigationExecutorTest {

    @Test
    void completesCpuSkillOnlyAfterRequiredMetricAndSourceEvidence() {
        Fixture fixture = fixture();
        RemoteEvidenceView cpu = evidence(EvidenceType.PROCESS_CPU_HIGH);
        when(fixture.evidenceClient.query(any(), any())).thenReturn(List.of(cpu));
        when(fixture.model.chooseSkill(any(), any(), any()))
                .thenReturn(new SkillDecision("jvm-cpu-hotspot", "CPU evidence routes to CPU Skill"));
        when(fixture.model.nextStep(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new StepDecision(StepAction.CALL_TOOL, "query_arthas_hot_threads", List.of(cpu.evidenceId()),
                        "Locate hot method"));
        DiagnosticObservation hot = new DiagnosticObservation(true, "hot method", java.util.Map.of(),
                EvidenceType.CPU_HOT_METHOD_FOUND, "arthas:order-service:hot-threads");
        when(fixture.toolExecutor.execute(any(), any(), any())).thenReturn(hot);
        RemoteEvidenceView hotEvidence = evidence(EvidenceType.CPU_HOT_METHOD_FOUND);
        when(fixture.evidenceClient.record(any(), any(), any(), any())).thenReturn(hotEvidence);
        List<UUID> progress = new ArrayList<>();

        TestProgressRecorder recorder = new TestProgressRecorder(progress);
        TaskOutcome result = fixture.executor.execute(task(), emptyProgress(), () -> false, recorder);

        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(result.evidenceIds()).containsExactly(cpu.evidenceId(), hotEvidence.evidenceId());
        assertThat(progress).containsExactly(hotEvidence.evidenceId());
        verify(fixture.toolExecutor).execute(any(), any(), any());
    }

    @Test
    void rejectsToolOutsideSelectedSkillWhitelist() {
        Fixture fixture = fixture();
        when(fixture.evidenceClient.query(any(), any())).thenReturn(List.of());
        when(fixture.model.chooseSkill(any(), any(), any()))
                .thenReturn(new SkillDecision("jvm-cpu-hotspot", "CPU objective"));
        when(fixture.model.nextStep(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new StepDecision(StepAction.CALL_TOOL, "query_arthas_waiting_threads", List.of(),
                        "wrong Tool"));

        TaskOutcome result = fixture.executor.execute(task(), emptyProgress(), () -> false,
                new TestProgressRecorder(new ArrayList<>()));

        assertThat(result.status()).isEqualTo(TaskStatus.INSUFFICIENT);
        assertThat(result.errorCode()).isEqualTo("STEP_DECISION_INVALID");
        assertThat(result.errorMessage()).contains("outside its Skill whitelist");
    }

    @Test
    void refusesPrematureModelCompletionWithoutLocalEvidenceGate() {
        Fixture fixture = fixture();
        when(fixture.evidenceClient.query(any(), any())).thenReturn(List.of(evidence(EvidenceType.PROCESS_CPU_HIGH)));
        when(fixture.model.chooseSkill(any(), any(), any()))
                .thenReturn(new SkillDecision("jvm-cpu-hotspot", "CPU objective"));
        when(fixture.model.nextStep(any(), any(), any(), any(), any(), any(Integer.class)))
                .thenReturn(new StepDecision(StepAction.COMPLETE, "", List.of(), "complete"));

        TaskOutcome result = fixture.executor.execute(task(), emptyProgress(), () -> false,
                new TestProgressRecorder(new ArrayList<>()));

        assertThat(result.status()).isEqualTo(TaskStatus.INSUFFICIENT);
        assertThat(result.errorCode()).isEqualTo("COMPLETION_EVIDENCE_MISSING");
    }

    @Test
    void resumesPersistedSkillAndReservedToolWithoutRepeatingModelDecisions() {
        Fixture fixture = fixture();
        UUID priorEvidenceId = UUID.randomUUID();
        JvmToolCallRecord reserved = new JvmToolCallRecord(UUID.randomUUID(), 0, "query_arthas_hot_threads",
                "task:0:query_arthas_hot_threads", ToolCallStatus.RESERVED, null,
                Instant.now().minusSeconds(1), Instant.now());
        JvmTaskProgress progress = new JvmTaskProgress("jvm-cpu-hotspot", List.of(reserved),
                List.of(priorEvidenceId), 1);
        RemoteEvidenceView cpu = new RemoteEvidenceView(priorEvidenceId, EvidenceType.PROCESS_CPU_HIGH.name(), "source",
                "high", java.util.Map.of(), Instant.now().minusSeconds(60), Instant.now());
        when(fixture.evidenceClient.query(any(), any())).thenReturn(List.of(cpu));
        DiagnosticObservation hot = new DiagnosticObservation(true, "hot", java.util.Map.of(),
                EvidenceType.CPU_HOT_METHOD_FOUND, "arthas:order-service:hot-threads");
        when(fixture.toolExecutor.execute(any(), any(), any())).thenReturn(hot);
        RemoteEvidenceView hotEvidence = evidence(EvidenceType.CPU_HOT_METHOD_FOUND);
        when(fixture.evidenceClient.record(any(), any(), any(), any())).thenReturn(hotEvidence);
        TestProgressRecorder recorder = new TestProgressRecorder(new ArrayList<>());

        TaskOutcome result = fixture.executor.execute(task(), progress, () -> false, recorder);

        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(recorder.completed).containsExactly(hotEvidence.evidenceId());
        verify(fixture.model, never()).chooseSkill(any(), any(), any());
        verify(fixture.model, never()).nextStep(any(), any(), any(), any(), any(), any(Integer.class));
        verify(fixture.evidenceClient).record(any(), any(), org.mockito.ArgumentMatchers.eq(reserved.toolCallId()), any());
    }

    private Fixture fixture() {
        JvmDiagnosticCatalog catalog = new JvmDiagnosticDefinitionLoader(
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()).load();
        JvmDiagnosticToolExecutor tools = mock(JvmDiagnosticToolExecutor.class);
        CentralEvidenceClient evidence = mock(CentralEvidenceClient.class);
        JvmReasoningModel model = mock(JvmReasoningModel.class);
        return new Fixture(new LoopJvmInvestigationExecutor(catalog, tools, evidence, model), tools, evidence, model);
    }

    private DelegationRequest task() {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "key", "1.0.0",
                new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(), "order-service",
                        "CPU high", new TimeRange(now.minusSeconds(60), now)), "Find CPU hotspot", List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(30)));
    }

    private JvmTaskProgress emptyProgress() {
        return new JvmTaskProgress("", List.of(), List.of(), 0);
    }

    private RemoteEvidenceView evidence(EvidenceType type) {
        Instant now = Instant.now();
        return new RemoteEvidenceView(UUID.randomUUID(), type.name(), "source", type.name(), java.util.Map.of(),
                now.minusSeconds(60), now);
    }

    private record Fixture(LoopJvmInvestigationExecutor executor, JvmDiagnosticToolExecutor toolExecutor,
                            CentralEvidenceClient evidenceClient, JvmReasoningModel model) {
    }

    private static final class TestProgressRecorder implements JvmInvestigationProgressRecorder {
        private final UUID remoteTaskId = UUID.randomUUID();
        private final List<UUID> completed;
        private int step;

        private TestProgressRecorder(List<UUID> completed) {
            this.completed = completed;
        }

        @Override
        public void selectSkill(String skillName) {
        }

        @Override
        public JvmToolCallRecord reserveTool(String toolName) {
            int current = step++;
            Instant now = Instant.now();
            return new JvmToolCallRecord(remoteTaskId, current, toolName, remoteTaskId + ":" + current + ":" + toolName,
                    ToolCallStatus.RESERVED, null, now, now);
        }

        @Override
        public void completeTool(JvmToolCallRecord call, UUID evidenceId) {
            if (evidenceId != null) {
                completed.add(evidenceId);
            }
        }
    }
}
