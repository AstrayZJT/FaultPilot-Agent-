package com.astrayzjt.faultpilot.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopIncidentOrchestratorTest {

    @Test
    void routesOnlyThroughTheThreeLoopBranches() {
        assertThat(LoopIncidentOrchestrator.route(MainAgentAction.DELEGATE)).isEqualTo("delegate");
        assertThat(LoopIncidentOrchestrator.route(MainAgentAction.COMPLETE)).isEqualTo("complete");
        assertThat(LoopIncidentOrchestrator.route(MainAgentAction.INCONCLUSIVE)).isEqualTo("inconclusive");
    }
}
