package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import java.util.List;

public class ConfiguredSpecialistAgent implements SpecialistAgent {

    private final SpecialistAgentRunner runner;
    private final AgentType type;

    public ConfiguredSpecialistAgent(SpecialistAgentRunner runner, AgentType type) {
        this.runner = runner;
        this.type = type;
    }

    @Override
    public AgentType type() {
        return type;
    }

    public AgentFinding investigate(AgentTask task, IncidentSnapshot snapshot, List<Evidence> evidence) {
        return runner.run(task, snapshot, evidence);
    }
}
