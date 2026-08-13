package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
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

class SummaryAgentTest {

    private final RemoteModelClient model = mock(RemoteModelClient.class);
    private final SummaryAgent agent = new SummaryAgent(model, new ObjectMapper().findAndRegisterModules());

    @Test
    void acceptsOnlyTheSummaryField() {
        when(model.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("prefix {\"summary\":\"Executor saturation is explained by blocked workers\"}");

        SummaryAgent.SummaryResult result = agent.summarize(incident(), draft(), List.of());

        assertThat(result.summary()).isEqualTo("Executor saturation is explained by blocked workers");
        assertThat(result.fallbackUsed()).isFalse();
    }

    @Test
    void repairsInvalidOutputOnceThenFallsBackToValidatedDraft() {
        when(model.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn("not-json", "{\"summary\":\"Repaired summary\"}");
        assertThat(agent.summarize(incident(), draft(), List.of()).summary()).isEqualTo("Repaired summary");

        RemoteModelClient unavailable = mock(RemoteModelClient.class);
        when(unavailable.complete(any(), any(), any(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("offline"));
        SummaryAgent.SummaryResult fallback = new SummaryAgent(unavailable,
                new ObjectMapper().findAndRegisterModules())
                .summarize(incident(), draft(), List.of());
        assertThat(fallback.summary()).isEqualTo(draft().summary());
        assertThat(fallback.fallbackUsed()).isTrue();
    }

    private IncidentSnapshot incident() {
        Instant now = Instant.now();
        return new IncidentSnapshot(UUID.randomUUID(), "order-service", "requests are blocked", null,
                new TimeRange(now.minusSeconds(60), now), null, null, null, false, now);
    }

    private DiagnosisDraft draft() {
        return new DiagnosisDraft(DiagnosisStatus.SUPPORTED, CauseCode.JVM_THREAD_POOL_EXHAUSTED,
                List.of(), List.of(UUID.randomUUID()), List.of(), List.of(),
                "Executor saturation is supported by active Evidence");
    }
}
