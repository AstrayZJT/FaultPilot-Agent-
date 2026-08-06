package com.astrayzjt.faultpilot.tool.registry;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolRegistry {

    private final Map<String, DiagnosticTool<?>> toolsByName;
    private final Map<AgentType, List<String>> toolsByAgent;

    public ToolRegistry(List<DiagnosticTool<?>> tools) {
        Map<String, DiagnosticTool<?>> byName = new HashMap<>();
        EnumMap<AgentType, List<String>> byAgent = new EnumMap<>(AgentType.class);
        for (DiagnosticTool<?> tool : tools) {
            if (tool.risk() != ToolRisk.READ_ONLY) {
                throw new IllegalStateException("Write-capable tool cannot be registered: " + tool.name());
            }
            if (byName.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate diagnostic tool: " + tool.name());
            }
            byAgent.computeIfAbsent(tool.owner(), ignored -> new java.util.ArrayList<>()).add(tool.name());
        }
        this.toolsByName = Map.copyOf(byName);
        this.toolsByAgent = Map.copyOf(byAgent);
    }

    public DiagnosticTool<?> require(String name, AgentType agentType) {
        DiagnosticTool<?> tool = toolsByName.get(name);
        if (tool == null || tool.owner() != agentType) {
            throw new IllegalArgumentException("Tool is not available to agent: " + name);
        }
        return tool;
    }

    public List<String> names(AgentType agentType) {
        return toolsByAgent.getOrDefault(agentType, List.of());
    }
}

