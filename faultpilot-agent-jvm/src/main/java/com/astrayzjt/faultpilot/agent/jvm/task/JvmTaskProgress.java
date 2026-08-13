package com.astrayzjt.faultpilot.agent.jvm.task;

import java.util.List;
import java.util.UUID;

public record JvmTaskProgress(
        String selectedSkill,
        List<JvmToolCallRecord> toolCalls,
        List<UUID> evidenceIds,
        int stepsUsed) {

    public JvmTaskProgress {
        selectedSkill = selectedSkill == null ? "" : selectedSkill.trim();
        toolCalls = toolCalls == null ? List.of() : toolCalls.stream()
                .sorted(java.util.Comparator.comparingInt(JvmToolCallRecord::stepIndex)).toList();
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        if (stepsUsed < 0 || stepsUsed > 10 || toolCalls.size() > stepsUsed) {
            throw new IllegalArgumentException("Invalid persisted JVM task progress");
        }
    }
}
