package com.astrayzjt.faultpilot.tool.declarative.catalog;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;

import java.util.List;

public interface ToolCatalog {
    ToolDefinition require(String toolName);

    List<ToolSummary> summaries(AgentType ownerAgent);

    boolean contains(String toolName);
}
