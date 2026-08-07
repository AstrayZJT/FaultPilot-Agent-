package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.CriticVerdict;
import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.ProposalStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGateTest {

    private final EvidenceGate gate = new EvidenceGate();

    @Test
    void confirmsCpuOnlyAfterHotMethodCorroboration() {
        UUID incidentId = UUID.randomUUID();
        Evidence cpu = evidence(incidentId, EvidenceType.PROCESS_CPU_HIGH);
        Evidence hotMethod = evidence(incidentId, EvidenceType.CPU_HOT_METHOD_FOUND);
        DiagnosisProposal proposal = proposal(incidentId, CauseCode.JVM_CPU_HOTSPOT, List.of(cpu, hotMethod));

        var result = gate.evaluate(proposal, pass(proposal.proposalId()), List.of(cpu, hotMethod));

        assertThat(result.status()).isEqualTo(com.astrayzjt.faultpilot.common.domain.DiagnosisStatus.CONFIRMED);
    }

    @Test
    void keepsAHighCpuSignalSupportedWhenCorroborationIsMissing() {
        UUID incidentId = UUID.randomUUID();
        Evidence cpu = evidence(incidentId, EvidenceType.PROCESS_CPU_HIGH);
        DiagnosisProposal proposal = proposal(incidentId, CauseCode.JVM_CPU_HOTSPOT, List.of(cpu));

        var result = gate.evaluate(proposal, pass(proposal.proposalId()), List.of(cpu));

        assertThat(result.status()).isEqualTo(com.astrayzjt.faultpilot.common.domain.DiagnosisStatus.SUPPORTED);
        assertThat(result.missingEvidenceTypes()).contains(EvidenceType.CPU_HOT_METHOD_FOUND);
    }

    @Test
    void confirmsClientPoolPressureWhenRedisServerLatencyIsNormal() {
        UUID incidentId = UUID.randomUUID();
        Evidence pending = evidence(incidentId, EvidenceType.REDIS_CLIENT_POOL_PENDING_HIGH);
        Evidence normalLatency = evidence(incidentId, EvidenceType.REDIS_COMMAND_LATENCY_NORMAL);
        DiagnosisProposal proposal = proposal(incidentId, CauseCode.REDIS_CLIENT_POOL_EXHAUSTED, List.of(pending, normalLatency));

        var result = gate.evaluate(proposal, pass(proposal.proposalId()), List.of(pending, normalLatency));

        assertThat(result.status()).isEqualTo(com.astrayzjt.faultpilot.common.domain.DiagnosisStatus.CONFIRMED);
    }

    private DiagnosisProposal proposal(UUID incidentId, CauseCode cause, List<Evidence> evidence) {
        return new DiagnosisProposal(UUID.randomUUID(), incidentId, 1, 0, ProposalStatus.READY_FOR_REVIEW, cause,
                List.of(), evidence.stream().map(Evidence::evidenceId).toList(), List.of(), List.of(), List.of(), "causal chain");
    }

    private DiagnosisCritique pass(UUID proposalId) {
        return new DiagnosisCritique(UUID.randomUUID(), proposalId, CriticVerdict.PASS, List.of(), "passed");
    }

    private Evidence evidence(UUID incidentId, EvidenceType type) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), incidentId, null, type, "test", "test", now.minusSeconds(5), now,
                type.name(), null, UUID.randomUUID().toString(), now);
    }
}
