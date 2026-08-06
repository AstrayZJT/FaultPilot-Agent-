package com.astrayzjt.faultpilot.orchestration;

import java.util.List;

public record InvestigationPlan(List<AgentTaskDraft> tasks, String reason) {
    public InvestigationPlan {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        reason = reason == null ? "" : reason;
    }
}
