package com.astrayzjt.faultpilot.agent.jvm.task;

import java.time.Instant;
import java.util.UUID;

public record JvmToolCallRecord(
        UUID remoteTaskId,
        int stepIndex,
        String toolName,
        String toolCallId,
        ToolCallStatus status,
        UUID evidenceId,
        Instant createdAt,
        Instant updatedAt) {

    public JvmToolCallRecord {
        if (remoteTaskId == null || stepIndex < 0 || toolName == null || toolName.isBlank()
                || toolCallId == null || toolCallId.isBlank() || status == null
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("Invalid persisted JVM Tool call");
        }
    }
}
