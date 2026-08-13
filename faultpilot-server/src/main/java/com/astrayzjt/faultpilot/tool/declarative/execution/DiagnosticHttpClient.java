package com.astrayzjt.faultpilot.tool.declarative.execution;

public interface DiagnosticHttpClient {
    DiagnosticHttpResponse execute(DiagnosticHttpRequest request);
}
