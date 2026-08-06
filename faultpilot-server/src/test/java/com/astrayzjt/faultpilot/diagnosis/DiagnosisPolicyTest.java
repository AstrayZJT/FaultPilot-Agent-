package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisPolicyTest {
    private final DiagnosisPolicy policy = new DiagnosisPolicy();

    @Test
    void confirmsOnlyCatalogEvidence() {
        Evidence evidence = evidence(EvidenceType.SLOW_SQL_FOUND);
        var decision = policy.evaluate(List.of(evidence));
        assertThat(decision.status()).isEqualTo(DiagnosisStatus.CONFIRMED);
        assertThat(decision.primaryCause()).isEqualTo(CauseCode.DB_SLOW_QUERY);
        assertThat(decision.supportingEvidenceIds()).containsExactly(evidence.evidenceId());
    }

    @Test
    void rejectsModelFreeConclusionWhenEvidenceIsMissing() {
        var decision = policy.evaluate(List.of(evidence(EvidenceType.THREAD_POOL_NORMAL)));
        assertThat(decision.status()).isEqualTo(DiagnosisStatus.INSUFFICIENT);
        assertThat(decision.primaryCause()).isEqualTo(CauseCode.UNKNOWN);
        assertThat(decision.supportingEvidenceIds()).isEmpty();
    }

    private Evidence evidence(EvidenceType type) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), UUID.randomUUID(), null, type, "test", "test", now.minusSeconds(5), now,
                type.name(), null, type.name(), now);
    }
}
