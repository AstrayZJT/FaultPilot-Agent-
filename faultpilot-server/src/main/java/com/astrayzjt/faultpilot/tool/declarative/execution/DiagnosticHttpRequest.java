package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record DiagnosticHttpRequest(
        ToolDefinition.HttpMethod method,
        URI uri,
        Map<String, String> headers,
        byte[] body,
        Duration timeout,
        int maxResponseBytes) {

    public DiagnosticHttpRequest {
        headers = Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
