package com.astrayzjt.faultpilot.tool.declarative.execution;

import java.util.Map;

public record DiagnosticHttpResponse(int statusCode, Map<String, String> headers, byte[] body) {

    public DiagnosticHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
