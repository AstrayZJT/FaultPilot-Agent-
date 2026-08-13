package com.astrayzjt.faultpilot.agent.distributed.domain;

public enum InvestigationRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    INCONCLUSIVE,
    FAILED,
    CANCELED,
    STALE;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, INCONCLUSIVE, FAILED, CANCELED, STALE -> true;
            case PENDING, RUNNING -> false;
        };
    }
}
