package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;

import java.util.Map;

public interface DiagnosticToolExecutor {

    ToolExecutionResult execute(ToolDefinition definition,
                                ToolExecutionContext context,
                                Map<String, Object> arguments);
}
