package com.astrayzjt.faultpilot.common.model;

/**
 * A remote model role was unavailable or could not produce a valid constrained response.
 */
public abstract class ModelInteractionException extends IllegalStateException {

    protected ModelInteractionException(String message) {
        super(message);
    }

    protected ModelInteractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
