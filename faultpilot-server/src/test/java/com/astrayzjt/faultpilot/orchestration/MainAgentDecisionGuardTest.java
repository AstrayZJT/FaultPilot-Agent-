package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainAgentDecisionGuardTest {

    private final CapabilityRegistry capabilities = registry();
    private final MainAgentDecisionGuard guard = new MainAgentDecisionGuard(capabilities);

    @Test
    void acceptsAvailableAgentAndCurrentEvidence() {
        Fixture fixture = fixture(List.of());
        MainAgentDecision decision = new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.JVM_AGENT,
                        "Inspect JVM worker blocking behind executor saturation")),
                List.of(fixture.evidence.evidenceId()),
                null, "Need source-level corroboration");

        assertThat(guard.validate(decision, fixture.context)).isSameAs(decision);
    }

    @Test
    void stripsAnIrrelevantDiagnosisDraftFromAValidDelegation() {
        Fixture fixture = fixture(List.of());
        MainAgentDecision decision = new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.JVM_AGENT,
                        "Inspect JVM worker blocking behind executor saturation")),
                List.of(fixture.evidence.evidenceId()),
                new DiagnosisDraft(DiagnosisStatus.SUPPORTED, CauseCode.JVM_THREAD_POOL_EXHAUSTED,
                        List.of(), List.of(fixture.evidence.evidenceId()), List.of(),
                        List.of(EvidenceType.BLOCKING_TASK_FOUND), "Preliminary diagnosis"),
                "Need source-level corroboration");

        MainAgentDecision normalized = guard.validate(decision, fixture.context);

        assertThat(normalized.action()).isEqualTo(MainAgentAction.DELEGATE);
        assertThat(normalized.delegations()).isEqualTo(decision.delegations());
        assertThat(normalized.evidenceIds()).isEqualTo(decision.evidenceIds());
        assertThat(normalized.draft()).isNull();
    }

    @Test
    void rejectsUnavailableAgentDangerousObjectiveAndForeignEvidence() {
        Fixture fixture = fixture(List.of());
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.DATABASE_AGENT, "Inspect database")),
                List.of(), null, "route"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.JVM_AGENT, "curl http://internal/admin")),
                List.of(), null, "route"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers")),
                List.of(UUID.randomUUID()), null, "route"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");
    }

    @Test
    void rejectsDuplicateDelegationWithoutNewInvestigationObjective() {
        String objective = "Inspect JVM worker blocking behind executor saturation";
        Fixture initial = fixture(List.of());
        AgentDelegation previous = delegation(initial, objective);
        Fixture fixture = fixture(List.of(previous));

        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(new SpecialistDelegation(AgentType.JVM_AGENT, objective)),
                List.of(), null, "repeat"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated");
    }

    @Test
    void rejectsMoreThanThreeParallelDelegationsAndDuplicatesWithinOneRound() {
        Fixture fixture = fixture(List.of());
        SpecialistDelegation delegation = new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers");
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(delegation, delegation), List.of(), null, "parallel"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same round");

        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                List.of(
                        new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers one"),
                        new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers two"),
                        new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers three"),
                        new SpecialistDelegation(AgentType.JVM_AGENT, "Inspect JVM workers four")),
                List.of(), null, "parallel"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 3");
    }

    @Test
    void requestsJvmCorroborationBeforeAcceptingAnUnsupportedCompletion() {
        Fixture fixture = fixture(List.of());
        MainAgentDecision unsupported = new MainAgentDecision(MainAgentAction.COMPLETE, List.of(), List.of(),
                new DiagnosisDraft(DiagnosisStatus.CONFIRMED, CauseCode.JVM_CPU_HOTSPOT, List.of(),
                        List.of(), List.of(), List.of(), "CPU is high"), "done");

        assertThat(guard.validate(unsupported, fixture.context).action()).isEqualTo(MainAgentAction.INCONCLUSIVE);

        MainAgentDecision supported = new MainAgentDecision(MainAgentAction.COMPLETE, List.of(),
                List.of(fixture.evidence.evidenceId()),
                new DiagnosisDraft(DiagnosisStatus.CONFIRMED, CauseCode.JVM_CPU_HOTSPOT, List.of(),
                        List.of(fixture.evidence.evidenceId()), List.of(), List.of(), "CPU hotspot is confirmed"),
                "done");
        MainAgentDecision normalized = guard.validate(supported, fixture.context);
        assertThat(normalized.action()).isEqualTo(MainAgentAction.DELEGATE);
        assertThat(normalized.delegations()).singleElement().satisfies(delegation -> {
            assertThat(delegation.agentType()).isEqualTo(AgentType.JVM_AGENT);
            assertThat(delegation.objective()).contains("CPU_HOT_METHOD_FOUND");
        });

        MainAgentDecision modelSupported = new MainAgentDecision(MainAgentAction.COMPLETE, List.of(),
                List.of(fixture.evidence.evidenceId()),
                new DiagnosisDraft(DiagnosisStatus.SUPPORTED, CauseCode.JVM_CPU_HOTSPOT, List.of(),
                        List.of(fixture.evidence.evidenceId()), List.of(), List.of(), "CPU hotspot is supported"),
                "done");
        assertThat(guard.validate(modelSupported, fixture.context).action()).isEqualTo(MainAgentAction.DELEGATE);
    }

    @Test
    void ignoresNormalEvidenceProducedByAnotherAgentDomainAndRequestsJvmCorroboration() {
        Fixture fixture = fixture(List.of());
        Evidence saturated = evidence(fixture, "jvm-agent", "query_prometheus_thread_pool",
                EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, "JVM executor is saturated");
        Evidence databaseNormal = evidence(fixture, "database-agent", "query_database_overview",
                EvidenceType.THREAD_POOL_NORMAL, "Database overview is normal");
        MainAgentContext context = contextWith(fixture, List.of(saturated, databaseNormal));
        MainAgentDecision decision = completion(saturated, CauseCode.JVM_THREAD_POOL_EXHAUSTED);

        MainAgentDecision result = guard.validate(decision, context);

        assertThat(result.action()).isEqualTo(MainAgentAction.DELEGATE);
        assertThat(result.delegations()).singleElement().satisfies(delegation -> {
            assertThat(delegation.agentType()).isEqualTo(AgentType.JVM_AGENT);
            assertThat(delegation.objective()).contains("BLOCKING_TASK_FOUND");
        });
    }

    @Test
    void rejectsNormalEvidenceProducedByTheProposedAgentDomain() {
        Fixture fixture = fixture(List.of());
        Evidence saturated = evidence(fixture, "jvm-agent", "query_prometheus_thread_pool",
                EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, "JVM executor is saturated");
        Evidence jvmNormal = evidence(fixture, "jvm-agent", "query_prometheus_thread_pool",
                EvidenceType.THREAD_POOL_NORMAL, "JVM executor is normal");
        MainAgentContext context = contextWith(fixture, List.of(saturated, jvmNormal));
        MainAgentDecision decision = completion(saturated, CauseCode.JVM_THREAD_POOL_EXHAUSTED);

        MainAgentDecision result = guard.validate(decision, context);

        assertThat(result.action()).isEqualTo(MainAgentAction.INCONCLUSIVE);
        assertThat(result.reason()).contains("contradict");
    }

    private MainAgentDecision completion(Evidence supporting, CauseCode cause) {
        return new MainAgentDecision(MainAgentAction.COMPLETE, List.of(), List.of(supporting.evidenceId()),
                new DiagnosisDraft(DiagnosisStatus.CONFIRMED, cause, List.of(),
                        List.of(supporting.evidenceId()), List.of(), List.of(), "Evidence supports the cause"),
                "complete");
    }

    private MainAgentContext contextWith(Fixture fixture, List<Evidence> evidence) {
        return new MainAgentContext(fixture.context.incident(), fixture.context.run(),
                fixture.context.capabilities(), evidence, fixture.context.delegations(),
                fixture.context.nextRound(), fixture.context.maxRounds(), fixture.context.maxDelegations(),
                fixture.context.deadline());
    }

    private Evidence evidence(Fixture fixture, String agentId, String toolId, EvidenceType type, String summary) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), fixture.context.incident().incidentId(), null,
                fixture.context.run().runId(), agentId, toolId, "test-tool-call", "test-1.0.0",
                EvidenceStatus.ACTIVE, type, "test:" + toolId, "order-service", now.minusSeconds(1), now,
                summary, null, UUID.randomUUID().toString(), java.util.Map.of(), now);
    }

    private Fixture fixture(List<AgentDelegation> delegations) {
        UUID incidentId = delegations.isEmpty() ? UUID.randomUUID() : delegations.getFirst().incidentId();
        UUID runId = delegations.isEmpty() ? UUID.randomUUID() : delegations.getFirst().runId();
        UUID snapshotId = capabilities.currentSnapshot().snapshotId();
        Instant now = Instant.now();
        InvestigationRun run = new InvestigationRun(runId, incidentId, snapshotId, InvestigationRunStatus.RUNNING,
                BaselineStatus.COMPLETED, null, now.minusSeconds(2), null, 1);
        IncidentSnapshot incident = new IncidentSnapshot(incidentId, "order-service", "requests are slow", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        Evidence evidence = new Evidence(UUID.randomUUID(), incidentId, null, runId, "jvm-agent",
                "query_prometheus_process_cpu", "baseline:jvm:cpu", "jvm-1.0.0", EvidenceStatus.ACTIVE,
                EvidenceType.PROCESS_CPU_HIGH, "prometheus:order-service:process_cpu_usage", "order-service",
                now.minusSeconds(60), now, "Process CPU is high", null, "hash", java.util.Map.of(), now);
        MainAgentContext context = new MainAgentContext(incident, run, capabilities.currentSnapshot(),
                List.of(evidence), delegations, 1, 4, 8, now.plusSeconds(60));
        return new Fixture(context, evidence);
    }

    private AgentDelegation delegation(Fixture fixture, String objective) {
        String hash = DelegationIdentity.objectiveHash(objective);
        return new AgentDelegation(UUID.randomUUID(), fixture.context.run().runId(),
                fixture.context.incident().incidentId(), 1, "jvm-agent", AgentType.JVM_AGENT, "jvm-1.0.0",
                objective, hash, DelegationIdentity.idempotencyKey(fixture.context.run().runId(), 1,
                "jvm-agent", objective), null, DelegationStatus.INSUFFICIENT, null, Instant.now().minusSeconds(5),
                Instant.now(), null, null, 1);
    }

    private CapabilityRegistry registry() {
        CapabilityRegistry registry = new CapabilityRegistry();
        AgentCapability jvm = new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent",
                "Investigates JVM faults", URI.create("local://jvm-agent"), "1.0", "jvm-1.0.0",
                AgentAvailability.AVAILABLE, false);
        registry.initialize(new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.ACTIVE,
                List.of(jvm), Instant.now()));
        return registry;
    }

    private record Fixture(MainAgentContext context, Evidence evidence) {
    }
}
