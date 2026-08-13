package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedSkill;

import java.util.List;

public interface SkillCatalog {
    LoadedSkill require(String skillName);

    List<SkillSummary> summaries(AgentType ownerAgent);

    boolean contains(String skillName);
}
