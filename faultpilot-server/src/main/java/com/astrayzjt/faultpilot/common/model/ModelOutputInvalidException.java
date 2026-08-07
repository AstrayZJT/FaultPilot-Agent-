package com.astrayzjt.faultpilot.common.model;

import com.astrayzjt.faultpilot.common.domain.ModelRole;

public class ModelOutputInvalidException extends ModelInteractionException {

    private final ModelRole role;

    public ModelOutputInvalidException(ModelRole role) {
        super("Remote Qwen returned an invalid constrained response for " + role);
        this.role = role;
    }

    public ModelRole role() {
        return role;
    }
}
