package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.CriticVerdict;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.ProposalStatus;
import com.astrayzjt.faultpilot.common.domain.TimeRange;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiagnosisCriticTest {

    @Test
    void acceptsVerdictAndIssueAliasesWithoutTrustingUnknownEvidenceIds() {
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant now = Instant.now();
        Evidence evidence = new Evidence(evidenceId, incidentId, null, EvidenceType.PROCESS_CPU_HIGH,
                "prometheus:order-service:process_cpu_usage", "order-service", now.minusSeconds(60), now,
                "Process CPU is high", null, "hash", now);
        IncidentSnapshot snapshot = new IncidentSnapshot(incidentId, "order-service", "CPU is high", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        var proposal = new com.astrayzjt.faultpilot.common.domain.DiagnosisProposal(
                UUID.randomUUID(), incidentId, 1, 0, ProposalStatus.READY_FOR_REVIEW,
                CauseCode.JVM_CPU_HOTSPOT, List.of(), List.of(evidenceId), List.of(), List.of(), List.of(), "CPU evidence");
        RemoteModelClient modelClient = mock(RemoteModelClient.class);
        String response = """
                {
                  "verdict": "APPROVED",
                  "issues": [
                    {
                      "type": "MISSING_EVIDENCE",
                      "summary": "A hot method would improve corroboration",
                      "evidenceIds": ["%s", "%s"],
                      "missingEvidenceTypes": ["CPU_HOT_METHOD"],
                      "suggestedAgent": "JVM"
                    }
                  ],
                  "summary": "The proposal is acceptable"
                }
                """.formatted(evidenceId, UUID.randomUUID());
        when(modelClient.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(response);

        var critique = new DiagnosisCritic(modelClient, new ObjectMapper())
                .review(snapshot, proposal, List.of(evidence), List.of());

        assertThat(critique.verdict()).isEqualTo(CriticVerdict.PASS);
        assertThat(critique.issues()).hasSize(1);
        assertThat(critique.issues().getFirst().evidenceIds()).containsExactly(evidenceId);
        assertThat(critique.issues().getFirst().missingEvidenceTypes())
                .containsExactly(EvidenceType.CPU_HOT_METHOD_FOUND);
        assertThat(critique.issues().getFirst().suggestedAgent())
                .isEqualTo(com.astrayzjt.faultpilot.common.domain.AgentType.JVM_AGENT);
    }
}
