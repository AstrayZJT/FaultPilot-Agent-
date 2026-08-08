package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CriticVerdict;
import com.astrayzjt.faultpilot.common.domain.CritiqueIssue;
import com.astrayzjt.faultpilot.common.domain.CritiqueIssueType;
import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SupervisorPlannerTest {

    @Test
    void rejectsAnEmptyTaskListSoTheModelRepairPathCanRun() {
        SupervisorPlanner planner = new SupervisorPlanner(new ObjectMapper(), mock(RemoteModelClient.class));

        assertThatThrownBy(() -> planner.parse("{\"tasks\":[],\"reason\":\"no signal\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty investigation plan");
    }

    @Test
    void enforcesTheAgentSuggestedByCriticForTargetedFollowUp() {
        SupervisorPlanner planner = new SupervisorPlanner(new ObjectMapper(), mock(RemoteModelClient.class));
        UUID evidenceId = UUID.randomUUID();
        var critique = new DiagnosisCritique(UUID.randomUUID(), UUID.randomUUID(), CriticVerdict.FOLLOW_UP,
                List.of(new CritiqueIssue(CritiqueIssueType.MISSING_HIGH_VALUE_CHECK, "Inspect blocked JVM threads",
                        List.of(evidenceId), List.of(), AgentType.JVM_AGENT)), "more JVM evidence");
        var modelPlan = new InvestigationPlan(List.of(
                new AgentTaskDraft(AgentType.DEPENDENCY_AGENT, "inspect downstream", List.of())), "model plan");

        InvestigationPlan result = planner.enforceCriticFollowUp(modelPlan, critique, 2);

        assertThat(result.tasks()).singleElement().satisfies(task -> {
            assertThat(task.agentType()).isEqualTo(AgentType.JVM_AGENT);
            assertThat(task.evidenceIds()).containsExactly(evidenceId);
        });
    }
}
