package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.util.List;

public record SkillSummary(
        String name,
        String version,
        AgentType ownerAgent,
        String description,
        List<EvidenceType> triggerEvidenceTypes,
        List<EvidenceType> producesEvidenceTypes) {

    public SkillSummary {
        triggerEvidenceTypes = List.copyOf(triggerEvidenceTypes);
        producesEvidenceTypes = List.copyOf(producesEvidenceTypes);
    }
}
