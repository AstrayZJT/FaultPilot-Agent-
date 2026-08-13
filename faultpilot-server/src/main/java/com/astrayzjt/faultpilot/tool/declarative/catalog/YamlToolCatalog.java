package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedTool;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class YamlToolCatalog implements ToolCatalog {

    private final Map<String, ToolDefinition> definitions;
    private final Map<AgentType, List<ToolSummary>> summaries;

    public YamlToolCatalog(List<LoadedTool> tools) {
        Map<String, ToolDefinition> byName = new LinkedHashMap<>();
        EnumMap<AgentType, List<ToolSummary>> byAgent = new EnumMap<>(AgentType.class);
        for (LoadedTool loaded : tools) {
            ToolDefinition definition = loaded.definition();
            String name = definition.metadata().name();
            if (byName.put(name, definition) != null) {
                throw new IllegalArgumentException("Duplicate declarative tool: " + name);
            }
            ToolDefinition.EvidenceMapping mapping = definition.spec().response().evidence();
            LinkedHashSet<EvidenceType> evidenceTypes = new LinkedHashSet<>();
            if (mapping.trueType() != null) {
                evidenceTypes.add(mapping.trueType());
            }
            if (mapping.falseType() != null) {
                evidenceTypes.add(mapping.falseType());
            }
            ToolSummary summary = new ToolSummary(name, definition.metadata().version(),
                    definition.spec().ownerAgent(), definition.spec().description(), List.copyOf(evidenceTypes));
            byAgent.computeIfAbsent(definition.spec().ownerAgent(), ignored -> new ArrayList<>()).add(summary);
        }
        byAgent.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparing(ToolSummary::name)).toList());
        this.definitions = Map.copyOf(byName);
        this.summaries = Map.copyOf(byAgent);
    }

    @Override
    public ToolDefinition require(String toolName) {
        ToolDefinition definition = definitions.get(toolName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown declarative tool: " + toolName);
        }
        return definition;
    }

    @Override
    public List<ToolSummary> summaries(AgentType ownerAgent) {
        return summaries.getOrDefault(ownerAgent, List.of());
    }

    @Override
    public boolean contains(String toolName) {
        return definitions.containsKey(toolName);
    }
}
