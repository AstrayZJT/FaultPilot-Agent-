package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
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

class DiagnosisSynthesizerTest {

    @Test
    void acceptsFinalDiagnosisAliasesAndKeepsOnlyIncidentEvidence() {
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant now = Instant.now();
        Evidence evidence = new Evidence(evidenceId, incidentId, null, EvidenceType.PROCESS_CPU_HIGH,
                "prometheus:order-service:process_cpu_usage", "order-service", now.minusSeconds(60), now,
                "Process CPU is high", null, "hash", now);
        IncidentSnapshot snapshot = new IncidentSnapshot(incidentId, "order-service", "CPU is high", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        RemoteModelClient modelClient = mock(RemoteModelClient.class);
        String response = """
                {
                  "status": "CONFIRMED",
                  "primaryCause": "CPU_HOTSPOT",
                  "supportingEvidenceIds": ["%s", "%s"],
                  "counterEvidenceIds": [],
                  "missingEvidenceTypes": [],
                  "summary": "The process CPU signal supports a JVM hotspot"
                }
                """.formatted(evidenceId, UUID.randomUUID());
        when(modelClient.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(response);

        var proposal = new DiagnosisSynthesizer(modelClient, new ObjectMapper())
                .propose(snapshot, List.of(evidence), List.of(), List.of(), 1, 0, null);

        assertThat(proposal.status()).isEqualTo(com.astrayzjt.faultpilot.common.domain.ProposalStatus.READY_FOR_REVIEW);
        assertThat(proposal.primaryCause()).isEqualTo(CauseCode.JVM_CPU_HOTSPOT);
        assertThat(proposal.supportingEvidenceIds()).containsExactly(evidenceId);
        assertThat(proposal.requestedFollowUps()).isEmpty();
        assertThat(proposal.causalSummary()).contains("JVM hotspot");
    }

    @Test
    void normalizesRedisSignalAliasAsTheRedisLatencyCause() {
        UUID incidentId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        Instant now = Instant.now();
        Evidence evidence = new Evidence(evidenceId, incidentId, null, EvidenceType.REDIS_COMMAND_LATENCY_HIGH,
                "redis:lab-redis:command-latency", "order-service", now.minusSeconds(60), now,
                "Redis command latency is high", null, "hash", now);
        IncidentSnapshot snapshot = new IncidentSnapshot(incidentId, "order-service", "Redis is slow", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
        RemoteModelClient modelClient = mock(RemoteModelClient.class);
        String response = """
                {
                  "status": "SUPPORTED",
                  "primaryCause": "REDIS_COMMAND_LATENCY_HIGH",
                  "supportingEvidenceIds": ["%s"],
                  "contributingFactors": ["UNKNOWN", "REDIS_SERVER_LATENCY"],
                  "missingEvidenceTypes": ["REDIS_SLOW_COMMAND_FOUND"],
                  "summary": "Redis command latency exceeds the configured threshold"
                }
                """.formatted(evidenceId);
        when(modelClient.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(response);

        var proposal = new DiagnosisSynthesizer(modelClient, new ObjectMapper())
                .propose(snapshot, List.of(evidence), List.of(), List.of(), 1, 0, null);

        assertThat(proposal.status()).isEqualTo(com.astrayzjt.faultpilot.common.domain.ProposalStatus.READY_FOR_REVIEW);
        assertThat(proposal.primaryCause()).isEqualTo(CauseCode.REDIS_SERVER_LATENCY);
        assertThat(proposal.contributingFactors()).isEmpty();
        assertThat(proposal.supportingEvidenceIds()).containsExactly(evidenceId);
        assertThat(proposal.missingEvidenceTypes()).containsExactly(EvidenceType.REDIS_SLOW_COMMAND_FOUND);
    }
}
