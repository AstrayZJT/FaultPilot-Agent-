package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.distributed.protocol.DelegationRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public interface A2aAgentClient {

    A2aTaskSnapshot submit(URI agentUrl, String bearerToken, DelegationRequest request, Duration timeout);

    Optional<A2aTaskSnapshot> query(URI agentUrl, String bearerToken, String remoteTaskId, Duration timeout);

    void cancel(URI agentUrl, String bearerToken, String remoteTaskId, Duration timeout);
}
