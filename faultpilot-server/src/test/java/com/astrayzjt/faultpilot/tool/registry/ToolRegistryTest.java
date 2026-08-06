package com.astrayzjt.faultpilot.tool.registry;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {
    @Test
    void rejectsWriteCapableTool() {
        DiagnosticTool<Map<String, Object>> tool = new DiagnosticTool<>() {
            public String name() { return "write"; }
            public AgentType owner() { return AgentType.JVM_AGENT; }
            public ToolRisk risk() { return ToolRisk.WRITE_BLOCKED; }
            @SuppressWarnings("unchecked") public Class<Map<String, Object>> argumentType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
            public ToolResult execute(Map<String, Object> args, ToolExecutionContext context) { return null; }
        };
        assertThatThrownBy(() -> new ToolRegistry(java.util.List.of(tool)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCrossAgentToolAccess() {
        DiagnosticTool<Map<String, Object>> tool = new DiagnosticTool<>() {
            public String name() { return "jvm"; }
            public AgentType owner() { return AgentType.JVM_AGENT; }
            public ToolRisk risk() { return ToolRisk.READ_ONLY; }
            @SuppressWarnings("unchecked") public Class<Map<String, Object>> argumentType() { return (Class<Map<String, Object>>) (Class<?>) Map.class; }
            public ToolResult execute(Map<String, Object> args, ToolExecutionContext context) { return ToolResult.failure("test", "ok"); }
        };
        ToolRegistry registry = new ToolRegistry(java.util.List.of(tool));
        assertThatThrownBy(() -> registry.require("jvm", AgentType.DATABASE_AGENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
