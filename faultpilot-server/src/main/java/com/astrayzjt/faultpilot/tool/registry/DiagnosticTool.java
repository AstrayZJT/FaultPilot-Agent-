package com.astrayzjt.faultpilot.tool.registry;

import com.astrayzjt.faultpilot.common.domain.AgentType;

public interface DiagnosticTool<A> {
    String name();
    AgentType owner();
    ToolRisk risk();
    Class<A> argumentType();
    ToolResult execute(A arguments, ToolExecutionContext context);
}

