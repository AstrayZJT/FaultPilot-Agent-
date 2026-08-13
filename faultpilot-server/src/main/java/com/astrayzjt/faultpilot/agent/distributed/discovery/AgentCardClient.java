package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.protocol.AgentCard;

import java.net.URI;
import java.time.Duration;

public interface AgentCardClient {
    AgentCard fetch(URI cardUri, Duration timeout);
}
