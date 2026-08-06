package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PlanValidator {

    private static final int MAX_TASKS_PER_ROUND = 3;

    public InvestigationPlan validate(InvestigationPlan plan, List<Evidence> evidence) {
        if (plan.tasks().isEmpty() || plan.tasks().size() > MAX_TASKS_PER_ROUND) {
            throw new IllegalArgumentException("Investigation plan must contain between 1 and 3 tasks");
        }
        Set<UUID> availableEvidence = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
        Set<String> uniqueTasks = new HashSet<>();
        for (AgentTaskDraft task : plan.tasks()) {
            if (task.agentType() == null || task.objective() == null || task.objective().isBlank()) {
                throw new IllegalArgumentException("Every investigation task needs an agent and objective");
            }
            if (!uniqueTasks.add(task.agentType() + "|" + task.objective().trim().toLowerCase())) {
                throw new IllegalArgumentException("Duplicate investigation task");
            }
            if (!availableEvidence.containsAll(task.evidenceIds())) {
                throw new IllegalArgumentException("Plan references evidence outside this incident");
            }
        }
        return plan;
    }
}
