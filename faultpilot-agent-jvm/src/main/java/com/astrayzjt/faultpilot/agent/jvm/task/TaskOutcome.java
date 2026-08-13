package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;

import java.util.List;
import java.util.UUID;

public record TaskOutcome(TaskStatus status, List<UUID> evidenceIds, int stepsUsed,
                          String errorCode, String errorMessage) {
    public TaskOutcome {
        evidenceIds = evidenceIds == null ? List.of() : evidenceIds.stream().distinct().toList();
        if (status == null || !status.terminal()) {
            throw new IllegalArgumentException("Task outcome must be terminal");
        }
        if (status == TaskStatus.COMPLETED && evidenceIds.isEmpty()) {
            throw new IllegalArgumentException("Completed JVM task must reference Evidence");
        }
        stepsUsed = Math.max(0, Math.min(10, stepsUsed));
    }
}
