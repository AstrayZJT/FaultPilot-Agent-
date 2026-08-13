package com.astrayzjt.faultpilot.agent.distributed.domain;

import java.time.Instant;
import java.util.UUID;

public record InvestigationRun(
        UUID runId,
        UUID incidentId,
        UUID capabilitySnapshotId,
        InvestigationRunStatus status,
        BaselineStatus baselineStatus,
        String restartReason,
        Instant startedAt,
        Instant completedAt,
        long version) {

    public InvestigationRun {
        if (runId == null || incidentId == null || capabilitySnapshotId == null || status == null
                || baselineStatus == null || startedAt == null || version < 0) {
            throw new IllegalArgumentException("Invalid investigation run");
        }
        if (status.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("Terminal investigation run must have completedAt and active run must not");
        }
        if (restartReason != null && restartReason.length() > 64) {
            throw new IllegalArgumentException("restartReason must not exceed 64 characters");
        }
    }

    public static InvestigationRun pending(UUID incidentId, UUID capabilitySnapshotId, Instant now) {
        return new InvestigationRun(UUID.randomUUID(), incidentId, capabilitySnapshotId,
                InvestigationRunStatus.PENDING, BaselineStatus.PENDING, null, now, null, 0);
    }
}
