package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;

import java.util.Map;

public final class DeclarativeDiagnosticTool implements DiagnosticTool<Map<String, Object>> {

    private final ToolDefinition definition;
    private final DiagnosticToolExecutor executor;

    public DeclarativeDiagnosticTool(ToolDefinition definition, DiagnosticToolExecutor executor) {
        this.definition = definition;
        this.executor = executor;
    }

    @Override
    public String name() {
        return definition.metadata().name();
    }

    @Override
    public AgentType owner() {
        return definition.spec().ownerAgent();
    }

    @Override
    public ToolRisk risk() {
        return definition.spec().riskLevel();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<Map<String, Object>> argumentType() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments, ToolExecutionContext context) {
        context.throwIfExpired();
        return executor.execute(definition, context, arguments == null ? Map.of() : arguments).toToolResult();
    }
}
