package com.astrayzjt.faultpilot.agent.protocol;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.Evidence;

import java.util.List;

public interface SpecialistAgent {
    com.astrayzjt.faultpilot.common.domain.AgentType type();
    AgentFinding investigate(AgentTask task, IncidentSnapshot snapshot, List<Evidence> existingEvidence);
}

