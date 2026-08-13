package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.util.List;

public record ToolSummary(
        String name,
        String version,
        AgentType ownerAgent,
        String description,
        List<EvidenceType> producesEvidenceTypes) {

    public ToolSummary {
        producesEvidenceTypes = List.copyOf(producesEvidenceTypes);
    }
}
