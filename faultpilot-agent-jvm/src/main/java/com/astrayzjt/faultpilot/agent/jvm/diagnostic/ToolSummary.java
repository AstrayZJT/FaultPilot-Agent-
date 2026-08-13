package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.List;

public record ToolSummary(String name, String version, String description, List<EvidenceType> produces) {
}
