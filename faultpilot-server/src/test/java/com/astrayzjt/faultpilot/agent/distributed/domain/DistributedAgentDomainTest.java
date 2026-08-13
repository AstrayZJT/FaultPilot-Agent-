package com.astrayzjt.faultpilot.agent.distributed.domain;

import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistributedAgentDomainTest {

    @Test
    void comparesOnlyRecoveryRelevantCapabilityFields() {
        AgentCapability oldAgent = capability("jvm-1.0.0", AgentAvailability.AVAILABLE,
                URI.create("http://jvm-agent:8091/a2a"));
        AgentCapability relocated = capability("jvm-1.0.0", AgentAvailability.AVAILABLE,
                URI.create("http://jvm-agent-new:8091/a2a"));
        CapabilitySnapshot previous = snapshot(oldAgent);
        CapabilitySnapshot current = snapshot(relocated);

        assertThat(previous.sameCapabilities(current)).isTrue();
        assertThat(previous.sameCapabilities(snapshot(capability("jvm-1.1.0", AgentAvailability.AVAILABLE,
                relocated.url())))).isFalse();
        assertThat(previous.sameCapabilities(snapshot(capability("jvm-1.0.0", AgentAvailability.UNAVAILABLE,
                relocated.url())))).isFalse();
    }

    @Test
    void enforcesDelegationTransitionsAndArtifactIdentity() {
        assertThat(DelegationStatus.PENDING.canTransitionTo(DelegationStatus.RUNNING)).isTrue();
        assertThat(DelegationStatus.RUNNING.canTransitionTo(DelegationStatus.COMPLETED)).isTrue();
        assertThat(DelegationStatus.COMPLETED.canTransitionTo(DelegationStatus.RUNNING)).isFalse();
        assertThat(DelegationStatus.RUNNING.canTransitionTo(DelegationStatus.RUNNING)).isFalse();

        UUID taskId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        EvidenceReferenceArtifact artifact = new EvidenceReferenceArtifact(
                EvidenceReferenceArtifact.SCHEMA_VERSION, taskId, "jvm-agent", "jvm-1.0.0",
                DelegationStatus.COMPLETED, List.of(evidenceId, evidenceId), 2);
        AgentDelegation completed = delegation(taskId, artifact);

        assertThat(completed.artifact().evidenceIds()).containsExactly(evidenceId);
        EvidenceReferenceArtifact wrongAgent = new EvidenceReferenceArtifact(
                EvidenceReferenceArtifact.SCHEMA_VERSION, taskId, "database-agent", "jvm-1.0.0",
                DelegationStatus.COMPLETED, List.of(evidenceId), 2);
        assertThatThrownBy(() -> delegation(taskId, wrongAgent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
    }

    @Test
    void completedArtifactMustReferenceEvidence() {
        assertThatThrownBy(() -> new EvidenceReferenceArtifact(
                EvidenceReferenceArtifact.SCHEMA_VERSION, UUID.randomUUID(), "jvm-agent", "jvm-1.0.0",
                DelegationStatus.COMPLETED, List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must reference Evidence");
    }

    private AgentDelegation delegation(UUID taskId, EvidenceReferenceArtifact artifact) {
        return new AgentDelegation(taskId, UUID.randomUUID(), UUID.randomUUID(), 1, "jvm-agent",
                AgentType.JVM_AGENT, "jvm-1.0.0", "Investigate blocked threads",
                "a".repeat(64), "idempotency", "remote-1", DelegationStatus.COMPLETED, artifact,
                Instant.now().minusSeconds(1), Instant.now(), null, null, 2);
    }

    private CapabilitySnapshot snapshot(AgentCapability capability) {
        return new CapabilitySnapshot(UUID.randomUUID(), CapabilitySnapshotStatus.ACTIVE,
                List.of(capability), Instant.now());
    }

    private AgentCapability capability(String version, AgentAvailability availability, URI uri) {
        return new AgentCapability("jvm-agent", AgentType.JVM_AGENT, "JVM Agent",
                "Investigates JVM failures", uri, "1.0", version, availability, true);
    }
}
