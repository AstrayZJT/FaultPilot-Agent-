package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.List;

public record SkillDefinition(String apiVersion, String kind, DefinitionMetadata metadata, Spec spec) {

    public record Spec(String ownerAgent, String description, List<EvidenceType> triggerEvidenceTypes,
                       List<EvidenceType> producesEvidenceTypes, List<String> allowedTools,
                       Completion completion, Limits limits, String riskLevel) {
        public Spec {
            triggerEvidenceTypes = copy(triggerEvidenceTypes);
            producesEvidenceTypes = copy(producesEvidenceTypes);
            allowedTools = copy(allowedTools);
        }
    }

    public record Completion(List<EvidenceType> allOf, List<EvidenceType> anyOf) {
        public Completion {
            allOf = copy(allOf);
            anyOf = copy(anyOf);
        }
    }

    public record Limits(int maxSteps, int timeoutSeconds) {
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
