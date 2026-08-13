package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JvmDiagnosticCatalog {

    private final Map<String, ToolDefinition> tools;
    private final Map<String, LoadedSkill> skills;

    public JvmDiagnosticCatalog(List<ToolDefinition> toolDefinitions, List<LoadedSkill> skillDefinitions) {
        LinkedHashMap<String, ToolDefinition> toolMap = new LinkedHashMap<>();
        toolDefinitions.stream().sorted(java.util.Comparator.comparing(tool -> tool.metadata().name())).forEach(tool -> {
            if (toolMap.put(tool.metadata().name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate JVM diagnostic tool: " + tool.metadata().name());
            }
        });
        LinkedHashMap<String, LoadedSkill> skillMap = new LinkedHashMap<>();
        skillDefinitions.stream().sorted(java.util.Comparator.comparing(skill -> skill.definition().metadata().name()))
                .forEach(skill -> {
            if (skillMap.put(skill.definition().metadata().name(), skill) != null) {
                throw new IllegalArgumentException("Duplicate JVM diagnostic Skill: "
                        + skill.definition().metadata().name());
            }
        });
        this.tools = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(toolMap));
        this.skills = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(skillMap));
    }

    public ToolDefinition requireTool(String name) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown JVM diagnostic tool: " + name);
        }
        return tool;
    }

    public LoadedSkill requireSkill(String name) {
        LoadedSkill skill = skills.get(name);
        if (skill == null) {
            throw new IllegalArgumentException("Unknown JVM diagnostic Skill: " + name);
        }
        return skill;
    }

    public List<SkillSummary> skillSummaries() {
        return skills.values().stream().map(value -> {
            SkillDefinition definition = value.definition();
            return new SkillSummary(definition.metadata().name(), definition.metadata().version(),
                    definition.spec().description(), definition.spec().triggerEvidenceTypes(),
                    definition.spec().producesEvidenceTypes());
        }).toList();
    }

    public List<ToolSummary> toolSummaries(LoadedSkill skill) {
        return skill.definition().spec().allowedTools().stream().map(this::requireTool).map(tool -> {
            ToolDefinition.EvidenceMapping evidence = tool.spec().response().evidence();
            return new ToolSummary(tool.metadata().name(), tool.metadata().version(), tool.spec().description(),
                    java.util.stream.Stream.of(evidence.trueType(), evidence.falseType())
                            .filter(java.util.Objects::nonNull).distinct().toList());
        }).toList();
    }
}
