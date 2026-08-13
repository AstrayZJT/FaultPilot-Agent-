package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedSkill;
import com.astrayzjt.faultpilot.tool.declarative.model.SkillDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class YamlSkillCatalog implements SkillCatalog {

    private final Map<String, LoadedSkill> definitions;
    private final Map<AgentType, List<SkillSummary>> summaries;

    public YamlSkillCatalog(List<LoadedSkill> skills) {
        Map<String, LoadedSkill> byName = new LinkedHashMap<>();
        EnumMap<AgentType, List<SkillSummary>> byAgent = new EnumMap<>(AgentType.class);
        for (LoadedSkill loaded : skills) {
            SkillDefinition definition = loaded.definition();
            String name = definition.metadata().name();
            if (byName.put(name, loaded) != null) {
                throw new IllegalArgumentException("Duplicate diagnostic skill: " + name);
            }
            SkillDefinition.Spec spec = definition.spec();
            SkillSummary summary = new SkillSummary(name, definition.metadata().version(), spec.ownerAgent(),
                    spec.description(), spec.triggerEvidenceTypes(), spec.producesEvidenceTypes());
            byAgent.computeIfAbsent(spec.ownerAgent(), ignored -> new ArrayList<>()).add(summary);
        }
        byAgent.replaceAll((ignored, values) -> values.stream()
                .sorted(Comparator.comparing(SkillSummary::name)).toList());
        this.definitions = Map.copyOf(byName);
        this.summaries = Map.copyOf(byAgent);
    }

    @Override
    public LoadedSkill require(String skillName) {
        LoadedSkill definition = definitions.get(skillName);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown diagnostic skill: " + skillName);
        }
        return definition;
    }

    @Override
    public List<SkillSummary> summaries(AgentType ownerAgent) {
        return summaries.getOrDefault(ownerAgent, List.of());
    }

    @Override
    public boolean contains(String skillName) {
        return definitions.containsKey(skillName);
    }
}
