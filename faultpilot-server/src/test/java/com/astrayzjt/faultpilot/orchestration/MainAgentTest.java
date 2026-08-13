package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MainAgentTest {

    private final MainAgent agent = new MainAgent(mock(RemoteModelClient.class), new ObjectMapper());

    @Test
    void parsesOneDelegationWithoutExposingToolDetails() {
        UUID evidenceId = UUID.randomUUID();

        MainAgentDecision decision = agent.parse("""
                {
                  "action": "DELEGATE",
                  "agentType": "JVM_AGENT",
                  "objective": "Determine whether executor workers are blocked",
                  "evidenceIds": ["%s"],
                  "diagnosis": null,
                  "reason": "Thread-pool evidence needs JVM corroboration"
                }
                """.formatted(evidenceId));

        assertThat(decision.action()).isEqualTo(MainAgentAction.DELEGATE);
        assertThat(decision.agentType()).isEqualTo(AgentType.JVM_AGENT);
        assertThat(decision.objective()).isEqualTo("Determine whether executor workers are blocked");
        assertThat(decision.evidenceIds()).containsExactly(evidenceId);
        assertThat(decision.draft()).isNull();
    }

    @Test
    void parsesEvidenceBoundCompletionDraft() {
        UUID supporting = UUID.randomUUID();
        UUID counter = UUID.randomUUID();

        MainAgentDecision decision = agent.parse("""
                {
                  "action": "COMPLETE",
                  "agentType": null,
                  "objective": "",
                  "evidenceIds": ["%s", "%s"],
                  "diagnosis": {
                    "status": "CONFIRMED",
                    "primaryCause": "JVM_THREAD_POOL_EXHAUSTED",
                    "contributingFactors": [],
                    "supportingEvidenceIds": ["%s"],
                    "counterEvidenceIds": ["%s"],
                    "missingEvidenceTypes": ["ABNORMAL_EXECUTION_PLAN"],
                    "summary": "Executor saturation has a blocking-task explanation"
                  },
                  "reason": "Direct JVM evidence is sufficient"
                }
                """.formatted(supporting, counter, supporting, counter));

        assertThat(decision.action()).isEqualTo(MainAgentAction.COMPLETE);
        assertThat(decision.draft().status()).isEqualTo(DiagnosisStatus.CONFIRMED);
        assertThat(decision.draft().primaryCause()).isEqualTo(CauseCode.JVM_THREAD_POOL_EXHAUSTED);
        assertThat(decision.draft().supportingEvidenceIds()).containsExactly(supporting);
        assertThat(decision.draft().counterEvidenceIds()).containsExactly(counter);
        assertThat(decision.draft().missingEvidenceTypes()).containsExactly(EvidenceType.ABNORMAL_EXECUTION_PLAN);
    }

    @Test
    void rejectsUnknownActionsAndMalformedEvidenceIds() {
        assertThatThrownBy(() -> agent.parse("{\"action\":\"CALL_TOOL\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid decision");
        assertThatThrownBy(() -> agent.parse("""
                {"action":"INCONCLUSIVE","evidenceIds":["not-a-uuid"]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid decision");
    }
}
