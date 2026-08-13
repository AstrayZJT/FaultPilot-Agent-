package com.astrayzjt.faultpilot.tool.declarative.loader;

import com.astrayzjt.faultpilot.tool.declarative.catalog.SkillCatalog;
import com.astrayzjt.faultpilot.tool.declarative.catalog.ToolCatalog;

public record DiagnosticCatalogBundle(ToolCatalog tools, SkillCatalog skills) {
}
