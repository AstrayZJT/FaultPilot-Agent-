package com.astrayzjt.faultpilot.agent.distributed.transport;

public final class A2aClientException extends RuntimeException {

    private final boolean retryable;

    public A2aClientException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public A2aClientException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
