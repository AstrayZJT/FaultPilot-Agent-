package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.discovery.AgentDiscoveryProperties;
import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.distributed.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(prefix = "faultpilot.agents", name = "transport", havingValue = "A2A")
public final class A2aSpecialistTransport implements SpecialistTransport {

    private static final int DEFAULT_MAX_STEPS = 4;

    private final A2aAgentClient client;
    private final CapabilityRegistry capabilities;
    private final AgentDiscoveryProperties properties;

    public A2aSpecialistTransport(A2aAgentClient client, CapabilityRegistry capabilities,
                                  AgentDiscoveryProperties properties) {
        this.client = client;
        this.capabilities = capabilities;
        this.properties = properties;
    }

    @Override
    public A2aTaskSnapshot submit(AgentDelegation delegation, IncidentSnapshot incident,
                                  List<Evidence> activeEvidence, Instant deadline) {
        AgentCapability agent = requireAgent(delegation);
        DelegationRequest request = new DelegationRequest(DelegationRequest.SCHEMA_VERSION,
                delegation.delegationId(), delegation.idempotencyKey(), delegation.capabilityVersion(),
                new DelegationRequest.IncidentContext(incident.incidentId(), delegation.runId(),
                        incident.serviceName(), incident.symptom(), incident.timeRange()), delegation.objective(),
                activeEvidence.stream().map(Evidence::evidenceId).toList(),
                new DelegationRequest.Limits(DEFAULT_MAX_STEPS, deadline));
        return retry(deadline, () -> client.submit(agent.url(), token(agent.agentId()), request,
                requestTimeout(deadline)));
    }

    @Override
    public Optional<A2aTaskSnapshot> query(AgentDelegation delegation, Instant deadline) {
        if (delegation.remoteTaskId() == null || delegation.remoteTaskId().isBlank()) {
            return Optional.empty();
        }
        AgentCapability agent = requireAgent(delegation);
        return retry(deadline, () -> client.query(agent.url(), token(agent.agentId()),
                delegation.remoteTaskId(), requestTimeout(deadline)));
    }

    @Override
    public void cancel(AgentDelegation delegation, Instant deadline) {
        if (delegation.remoteTaskId() == null || delegation.remoteTaskId().isBlank()) {
            return;
        }
        AgentCapability agent = requireAgent(delegation);
        if (agent.supportsCancel()) {
            retry(deadline, () -> {
                client.cancel(agent.url(), token(agent.agentId()), delegation.remoteTaskId(),
                        requestTimeout(deadline));
                return null;
            });
        }
    }

    private AgentCapability requireAgent(AgentDelegation delegation) {
        AgentCapability capability = capabilities.requireAvailable(delegation.agentType());
        if (!capability.agentId().equals(delegation.agentId())
                || !capability.capabilityVersion().equals(delegation.capabilityVersion())) {
            throw new IllegalArgumentException("Delegation does not match the active Agent capability");
        }
        return capability;
    }

    private String token(String agentId) {
        AgentDiscoveryProperties.RemoteAgentProperties endpoint = properties.getEndpoints().get(agentId);
        return endpoint == null ? null : endpoint.getBearerToken();
    }

    private Duration requestTimeout(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw new A2aClientException("A2A task deadline has expired", false);
        }
        Duration configured = Duration.ofSeconds(properties.getRequestTimeoutSeconds());
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    private <T> T retry(Instant deadline, Supplier<T> operation) {
        A2aClientException last = null;
        for (int attempt = 0; attempt <= properties.getMaxRetries(); attempt++) {
            try {
                return operation.get();
            } catch (A2aClientException exception) {
                last = exception;
                if (!exception.retryable() || attempt == properties.getMaxRetries()) {
                    throw exception;
                }
                pause(deadline, 100L * (attempt + 1));
            }
        }
        throw last == null ? new A2aClientException("A2A operation failed", false) : last;
    }

    private void pause(Instant deadline, long millis) {
        long remaining = Duration.between(Instant.now(), deadline).toMillis();
        if (remaining <= 0) {
            throw new A2aClientException("A2A task deadline has expired", false);
        }
        try {
            Thread.sleep(Math.min(millis, remaining));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new A2aClientException("A2A retry was interrupted", false, exception);
        }
    }
}
