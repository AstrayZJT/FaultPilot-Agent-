package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.catalog.ToolCatalog;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;

import java.util.Map;

public final class DeclarativeToolInvoker {

    private final ToolCatalog catalog;
    private final DiagnosticToolExecutor executor;

    public DeclarativeToolInvoker(ToolCatalog catalog, DiagnosticToolExecutor executor) {
        this.catalog = catalog;
        this.executor = executor;
    }

    public ToolExecutionResult execute(String toolName, ToolExecutionContext context,
                                       Map<String, Object> arguments) {
        ToolDefinition definition = catalog.require(toolName);
        if (definition.spec().ownerAgent() != context.agentType()) {
            throw new IllegalArgumentException("Tool is not available to agent: " + toolName);
        }
        return executor.execute(definition, context, arguments == null ? Map.of() : arguments);
    }
}
