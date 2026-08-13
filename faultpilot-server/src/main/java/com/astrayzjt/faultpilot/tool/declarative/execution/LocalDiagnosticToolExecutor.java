package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;

import java.util.Map;

public final class LocalDiagnosticToolExecutor implements DiagnosticToolExecutor {

    private final ToolRegistry toolRegistry;

    public LocalDiagnosticToolExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ToolExecutionResult execute(ToolDefinition definition, ToolExecutionContext context,
                                       Map<String, Object> arguments) {
        context.throwIfExpired();
        if (definition.spec().ownerAgent() != context.agentType()) {
            throw new IllegalArgumentException("Declarative tool owner does not match execution agent");
        }
        DiagnosticTool<Map<String, Object>> tool = (DiagnosticTool<Map<String, Object>>) toolRegistry.require(
                definition.metadata().name(), definition.spec().ownerAgent());
        var result = tool.execute(arguments == null ? Map.of() : arguments, context);
        int status = result.success() ? 200 : 0;
        return new ToolExecutionResult(result.success(), status, result.summary(), result.data(),
                result.evidenceType(), result.source());
    }
}
