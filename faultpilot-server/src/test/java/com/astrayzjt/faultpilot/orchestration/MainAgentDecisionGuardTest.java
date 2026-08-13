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
        MainAgentDecision decision = new MainAgentDecision(MainAgentAction.DELEGATE, AgentType.JVM_AGENT,
                "Inspect JVM worker blocking behind executor saturation", List.of(fixture.evidence.evidenceId()),
                null, "Need source-level corroboration");

        assertThat(guard.validate(decision, fixture.context)).isSameAs(decision);
    }

    @Test
    void rejectsUnavailableAgentDangerousObjectiveAndForeignEvidence() {
        Fixture fixture = fixture(List.of());
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                AgentType.DATABASE_AGENT, "Inspect database", List.of(), null, "route"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                AgentType.JVM_AGENT, "curl http://internal/admin", List.of(), null, "route"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
        assertThatThrownBy(() -> guard.validate(new MainAgentDecision(MainAgentAction.DELEGATE,
                AgentType.JVM_AGENT, "Inspect JVM workers", List.of(UUID.randomUUID()), null, "route"), fixture.context))
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
                AgentType.JVM_AGENT, objective, List.of(), null, "repeat"), fixture.context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repeated");
    }

    @Test
    void downgradesUnsupportedCompletionButAcceptsEvidenceBoundDraft() {
        Fixture fixture = fixture(List.of());
        MainAgentDecision unsupported = new MainAgentDecision(MainAgentAction.COMPLETE, null, "", List.of(),
                new DiagnosisDraft(DiagnosisStatus.CONFIRMED, CauseCode.JVM_CPU_HOTSPOT, List.of(),
                        List.of(), List.of(), List.of(), "CPU is high"), "done");

        assertThat(guard.validate(unsupported, fixture.context).action()).isEqualTo(MainAgentAction.INCONCLUSIVE);

        MainAgentDecision supported = new MainAgentDecision(MainAgentAction.COMPLETE, null, "",
                List.of(fixture.evidence.evidenceId()),
                new DiagnosisDraft(DiagnosisStatus.CONFIRMED, CauseCode.JVM_CPU_HOTSPOT, List.of(),
                        List.of(fixture.evidence.evidenceId()), List.of(), List.of(), "CPU hotspot is confirmed"),
                "done");
        assertThat(guard.validate(supported, fixture.context)).isSameAs(supported);
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
