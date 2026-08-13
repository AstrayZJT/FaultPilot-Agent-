package com.astrayzjt.faultpilot.agent.distributed.domain;

import java.util.EnumSet;

public enum DelegationStatus {
    PENDING,
    SUBMITTED,
    RUNNING,
    COMPLETED,
    INSUFFICIENT,
    TIMED_OUT,
    FAILED,
    CANCELED,
    STALE;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, INSUFFICIENT, TIMED_OUT, FAILED, CANCELED, STALE -> true;
            case PENDING, SUBMITTED, RUNNING -> false;
        };
    }

    public boolean canTransitionTo(DelegationStatus target) {
        if (target == null || this == target || terminal()) {
            return false;
        }
        return switch (this) {
            case PENDING -> EnumSet.of(SUBMITTED, RUNNING, FAILED, CANCELED, STALE).contains(target);
            case SUBMITTED -> EnumSet.of(RUNNING, COMPLETED, INSUFFICIENT, TIMED_OUT, FAILED, CANCELED, STALE)
                    .contains(target);
            case RUNNING -> EnumSet.of(COMPLETED, INSUFFICIENT, TIMED_OUT, FAILED, CANCELED, STALE).contains(target);
            case COMPLETED, INSUFFICIENT, TIMED_OUT, FAILED, CANCELED, STALE -> false;
        };
    }
}
