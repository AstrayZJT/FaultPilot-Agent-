package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;

import java.time.Instant;
import java.util.List;

public record MainAgentContext(
        IncidentSnapshot incident,
        InvestigationRun run,
        CapabilitySnapshot capabilities,
        List<Evidence> evidence,
        List<AgentDelegation> delegations,
        int nextRound,
        int maxRounds,
        int maxDelegations,
        Instant deadline) {

    public MainAgentContext {
        if (incident == null || run == null || capabilities == null || nextRound < 1
                || maxRounds < 1 || maxDelegations < 1 || deadline == null) {
            throw new IllegalArgumentException("Invalid Main Agent context");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        delegations = delegations == null ? List.of() : List.copyOf(delegations);
    }

    public int remainingRounds() {
        return Math.max(0, maxRounds - nextRound + 1);
    }

    public int remainingDelegations() {
        return Math.max(0, maxDelegations - delegations.size());
    }
}
