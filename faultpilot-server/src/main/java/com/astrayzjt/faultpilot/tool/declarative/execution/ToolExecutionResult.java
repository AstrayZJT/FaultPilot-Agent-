package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;

import java.util.Map;

public record ToolExecutionResult(
        boolean success,
        int httpStatus,
        String summary,
        Map<String, Object> data,
        EvidenceType evidenceType,
        String source) {

    public ToolExecutionResult {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public ToolResult toToolResult() {
        return new ToolResult(success, summary, data, evidenceType, source);
    }

    public static ToolExecutionResult failure(String source, String summary) {
        return new ToolExecutionResult(false, 0, summary, Map.of(), EvidenceType.DATA_UNAVAILABLE, source);
    }

    public static ToolExecutionResult failure(int httpStatus, String source, String summary) {
        return new ToolExecutionResult(false, httpStatus, summary, Map.of(),
                EvidenceType.DATA_UNAVAILABLE, source);
    }
}
