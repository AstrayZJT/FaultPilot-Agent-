package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpecialistTransport {

    A2aTaskSnapshot submit(AgentDelegation delegation, IncidentSnapshot incident,
                           List<Evidence> activeEvidence, Instant deadline);

    Optional<A2aTaskSnapshot> query(AgentDelegation delegation, Instant deadline);

    void cancel(AgentDelegation delegation, Instant deadline);
}
