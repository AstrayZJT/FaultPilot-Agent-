package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanValidatorTest {
    private final PlanValidator validator = new PlanValidator();

    @Test
    void rejectsDuplicateAgentObjectives() {
        var plan = new InvestigationPlan(List.of(
                new AgentTaskDraft(AgentType.JVM_AGENT, "inspect", List.of()),
                new AgentTaskDraft(AgentType.JVM_AGENT, "inspect", List.of())), "bad");
        assertThatThrownBy(() -> validator.validate(plan, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEvidenceFromAnotherIncident() {
        UUID evidenceId = UUID.randomUUID();
        Evidence available = new Evidence(evidenceId, UUID.randomUUID(), null, EvidenceType.PROCESS_CPU_HIGH,
                "test", "test", Instant.now().minusSeconds(5), Instant.now(), "cpu", null, "hash", Instant.now());
        var plan = new InvestigationPlan(List.of(new AgentTaskDraft(AgentType.JVM_AGENT, "inspect", List.of(UUID.randomUUID()))), "bad");
        assertThatThrownBy(() -> validator.validate(plan, List.of(available))).isInstanceOf(IllegalArgumentException.class);
    }
}
