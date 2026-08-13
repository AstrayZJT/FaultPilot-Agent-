package com.astrayzjt.faultpilot.agent.distributed.discovery;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentAvailability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshot;
import com.astrayzjt.faultpilot.agent.distributed.domain.CapabilitySnapshotStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.agent.distributed.protocol.AgentCard;
import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class CapabilityDiscoveryService implements ApplicationRunner {

    private final AgentDiscoveryProperties properties;
    private final List<SpecialistAgent> localAgents;
    private final AgentCardClient cardClient;
    private final CapabilitySnapshotRepository repository;
    private final CapabilityRegistry registry;

    public CapabilityDiscoveryService(AgentDiscoveryProperties properties,
                                      List<SpecialistAgent> localAgents,
                                      AgentCardClient cardClient,
                                      CapabilitySnapshotRepository repository,
                                      CapabilityRegistry registry) {
        this.properties = properties;
        this.localAgents = List.copyOf(localAgents);
        this.cardClient = cardClient;
        this.repository = repository;
        this.registry = registry;
    }

    @Override
    public void run(ApplicationArguments args) {
        CapabilitySnapshot snapshot = discover();
        repository.replaceCurrent(snapshot);
        registry.initialize(snapshot);
    }

    CapabilitySnapshot discover() {
        List<AgentCapability> capabilities = properties.getTransport() == AgentDiscoveryProperties.Transport.LOCAL
                ? discoverLocal() : discoverRemote();
        CapabilitySnapshotStatus status = capabilities.stream()
                .allMatch(agent -> agent.status() == AgentAvailability.AVAILABLE)
                ? CapabilitySnapshotStatus.ACTIVE : CapabilitySnapshotStatus.DEGRADED;
        return new CapabilitySnapshot(UUID.randomUUID(), status, capabilities, Instant.now());
    }

    private List<AgentCapability> discoverLocal() {
        if (localAgents.isEmpty()) {
            throw new IllegalStateException("LOCAL Agent transport requires at least one SpecialistAgent");
        }
        EnumMap<AgentType, SpecialistAgent> unique = new EnumMap<>(AgentType.class);
        for (SpecialistAgent agent : localAgents) {
            if (unique.put(agent.type(), agent) != null) {
                throw new IllegalStateException("Duplicate local SpecialistAgent: " + agent.type());
            }
        }
        Map<AgentType, String> versions = properties.getLocalVersions();
        return unique.keySet().stream().sorted(Comparator.comparing(Enum::name)).map(type -> {
            String id = type.name().toLowerCase().replace('_', '-');
            String version = versions.get(type);
            if (version == null || version.isBlank()) {
                throw new IllegalStateException("Missing local capabilityVersion for " + type);
            }
            return new AgentCapability(id, type, displayName(type), description(type), URI.create("local://" + id),
                    properties.getProtocolVersion(), version, AgentAvailability.AVAILABLE, false);
        }).toList();
    }

    private List<AgentCapability> discoverRemote() {
        if (properties.getEndpoints().isEmpty()) {
            throw new IllegalStateException("A2A Agent transport requires configured Agent Card endpoints");
        }
        List<AgentCapability> capabilities = new ArrayList<>();
        properties.getEndpoints().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            String configuredId = entry.getKey();
            AgentDiscoveryProperties.RemoteAgentProperties configured = entry.getValue();
            validateRemoteConfiguration(configuredId, configured);
            URI cardUri = URI.create(configured.getCardUrl());
            try {
                AgentCard card = cardClient.fetch(cardUri, Duration.ofSeconds(properties.getTimeoutSeconds()));
                AgentCapability capability = card.toCapability();
                if (!configuredId.equals(capability.agentId()) || configured.getAgentType() != capability.agentType()) {
                    throw new IllegalStateException("Agent Card identity does not match configuration: " + configuredId);
                }
                if (!properties.getProtocolVersion().equals(capability.protocolVersion())) {
                    throw new IllegalStateException("Unsupported Agent protocol version for " + configuredId);
                }
                capabilities.add(capability);
            } catch (RuntimeException exception) {
                if (configured.isRequired()) {
                    throw new IllegalStateException("Required Agent Card is unavailable: " + configuredId, exception);
                }
                capabilities.add(new AgentCapability(configuredId, configured.getAgentType(), configuredId,
                        "Optional Agent was unavailable during startup discovery", cardUri,
                        properties.getProtocolVersion(), "unavailable", AgentAvailability.UNAVAILABLE, false));
            }
        });
        return List.copyOf(capabilities);
    }

    private void validateRemoteConfiguration(String id, AgentDiscoveryProperties.RemoteAgentProperties configured) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{1,127}") || configured == null
                || configured.getAgentType() == null || configured.getCardUrl() == null
                || configured.getCardUrl().isBlank()) {
            throw new IllegalArgumentException("Invalid remote Agent discovery configuration: " + id);
        }
    }

    private String displayName(AgentType type) {
        return "FaultPilot " + switch (type) {
            case JVM_AGENT -> "JVM Agent";
            case DATABASE_AGENT -> "Database Agent";
            case DEPENDENCY_AGENT -> "Dependency Agent";
            case CACHE_AGENT -> "Cache Agent";
        };
    }

    private String description(AgentType type) {
        return switch (type) {
            case JVM_AGENT -> "Investigates JVM CPU, executor saturation, blocked threads and source locations.";
            case DATABASE_AGENT -> "Investigates database pools, slow statements, connection holders and traces.";
            case DEPENDENCY_AGENT -> "Investigates downstream latency, health and slow trace spans.";
            case CACHE_AGENT -> "Investigates Redis latency, client pools, memory, evictions and hit rate.";
        };
    }
}
