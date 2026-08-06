package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;

import java.time.Instant;
import java.util.UUID;

public record ActionExecutionContext(UUID incidentId, UUID pendingActionId, ActionCode actionCode,
                                     String targetService, Instant deadline) {
    public void throwIfExpired() {
        if (!Instant.now().isBefore(deadline)) {
            throw new IllegalStateException("Action execution deadline exceeded");
        }
    }
}
