package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.List;

public record SkillSummary(String name, String version, String description,
                           List<EvidenceType> triggerEvidenceTypes,
                           List<EvidenceType> producesEvidenceTypes) {
}
