package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
}
