package com.astrayzjt.faultpilot.agent.jvm.protocol;

public enum TaskStatus {
    SUBMITTED,
    RUNNING,
    COMPLETED,
    INSUFFICIENT,
    TIMED_OUT,
    FAILED,
    CANCELED;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, INSUFFICIENT, TIMED_OUT, FAILED, CANCELED -> true;
            case SUBMITTED, RUNNING -> false;
        };
    }
}
