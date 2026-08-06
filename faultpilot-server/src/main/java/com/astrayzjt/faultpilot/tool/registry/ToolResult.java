package com.astrayzjt.faultpilot.tool.registry;

import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.util.Map;

public record ToolResult(
        boolean success,
        String summary,
        Map<String, Object> data,
        EvidenceType evidenceType,
        String source) {

    public static ToolResult failure(String source, String summary) {
        return new ToolResult(false, summary, Map.of(), EvidenceType.DATA_UNAVAILABLE, source);
    }
}

