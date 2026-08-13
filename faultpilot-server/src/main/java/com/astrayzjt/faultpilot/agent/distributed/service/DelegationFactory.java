package com.astrayzjt.faultpilot.agent.distributed.service;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;

import java.util.UUID;

public final class DelegationFactory {

    public AgentDelegation create(InvestigationRun run, int round, AgentCapability agent, String objective) {
        String hash = DelegationIdentity.objectiveHash(objective);
        return new AgentDelegation(UUID.randomUUID(), run.runId(), run.incidentId(), round, agent.agentId(),
                agent.agentType(), agent.capabilityVersion(), objective.trim(), hash,
                DelegationIdentity.idempotencyKey(run.runId(), round, agent.agentId(), objective), null,
                DelegationStatus.PENDING, null, null, null, null, null, 0);
    }
}
