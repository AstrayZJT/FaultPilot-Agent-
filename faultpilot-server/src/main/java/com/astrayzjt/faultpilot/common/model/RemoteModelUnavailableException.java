package com.astrayzjt.faultpilot.common.model;

public class RemoteModelUnavailableException extends ModelInteractionException {

    public RemoteModelUnavailableException(String message) {
        super(message);
    }

    public RemoteModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
