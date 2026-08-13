package com.astrayzjt.faultpilot.tool.declarative.validation;

public class DiagnosticDefinitionException extends IllegalArgumentException {

    public DiagnosticDefinitionException(String message) {
        super(message);
    }

    public DiagnosticDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
