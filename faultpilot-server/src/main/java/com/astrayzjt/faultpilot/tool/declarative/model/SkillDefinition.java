package com.astrayzjt.faultpilot.tool.declarative.model;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;

import java.util.List;

public record SkillDefinition(
        String apiVersion,
        String kind,
        DefinitionMetadata metadata,
        Spec spec) {

    public record Spec(
            AgentType ownerAgent,
            String description,
            List<EvidenceType> triggerEvidenceTypes,
            List<EvidenceType> producesEvidenceTypes,
            List<String> allowedTools,
            Completion completion,
            Limits limits,
            ToolRisk riskLevel) {
        public Spec {
            triggerEvidenceTypes = immutable(triggerEvidenceTypes);
            producesEvidenceTypes = immutable(producesEvidenceTypes);
            allowedTools = immutable(allowedTools);
        }
    }

    public record Completion(List<EvidenceType> allOf, List<EvidenceType> anyOf) {
        public Completion {
            allOf = immutable(allOf);
            anyOf = immutable(anyOf);
        }
    }

    public record Limits(int maxSteps, int timeoutSeconds) {
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
