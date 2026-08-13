package com.astrayzjt.faultpilot.tool.declarative.execution;

public interface EndpointCatalog {
    DiagnosticEndpoint require(String endpointRef);
}
