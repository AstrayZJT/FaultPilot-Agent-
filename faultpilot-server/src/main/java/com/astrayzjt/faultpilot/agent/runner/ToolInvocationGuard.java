package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.common.domain.AgentStepAction;
import com.astrayzjt.faultpilot.common.domain.AgentStepDecision;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ToolInvocationGuard {

    private final ToolRegistry toolRegistry;

    public ToolInvocationGuard(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public void validate(AgentStepDecision decision, AgentType agentType, Set<String> calledTools) {
        if (decision.action() != AgentStepAction.CALL_TOOL) {
            return;
        }
        if (decision.toolName().isBlank()) {
            throw new IllegalArgumentException("CALL_TOOL decision must contain a toolName");
        }
        if (!toolRegistry.names(agentType).contains(decision.toolName())) {
            throw new IllegalArgumentException("Model selected a tool outside the agent allow-list: " + decision.toolName());
        }
        if (!calledTools.add(decision.toolName())) {
            throw new IllegalArgumentException("The same diagnostic tool cannot be called twice in one task: " + decision.toolName());
        }
        JsonNode arguments = decision.arguments();
        if (arguments != null && !arguments.isObject()) {
            throw new IllegalArgumentException("Diagnostic tool arguments must be a JSON object");
        }
        if (arguments != null && arguments.size() > 0) {
            throw new IllegalArgumentException("This diagnostic tool does not accept model-generated arguments");
        }
    }
}
