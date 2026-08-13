package com.astrayzjt.faultpilot.agent.jvm.reasoning;

public record SkillDecision(String skillName, String rationale) {
    public SkillDecision {
        skillName = skillName == null ? "" : skillName.trim();
        rationale = rationale == null ? "" : rationale.substring(0, Math.min(300, rationale.length()));
    }
}
