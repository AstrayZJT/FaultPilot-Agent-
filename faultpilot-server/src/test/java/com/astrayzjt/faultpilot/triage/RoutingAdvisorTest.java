package com.astrayzjt.faultpilot.triage;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingAdvisorTest {

    private final RoutingAdvisor advisor = new RoutingAdvisor();

    @Test
    void prioritizesCacheAgentForRedisPressure() {
        var signals = advisor.derive(List.of(evidence(EvidenceType.REDIS_CLIENT_POOL_PENDING_HIGH)));

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.agentType()).isEqualTo(AgentType.CACHE_AGENT);
            assertThat(signal.score()).isPositive();
        });
    }

    @Test
    void recordsNormalCacheSignalAsNegativeEvidence() {
        var signals = advisor.derive(List.of(evidence(EvidenceType.REDIS_COMMAND_LATENCY_NORMAL)));

        assertThat(signals).singleElement().satisfies(signal -> {
            assertThat(signal.agentType()).isEqualTo(AgentType.CACHE_AGENT);
            assertThat(signal.score()).isNegative();
        });
    }

    private Evidence evidence(EvidenceType type) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), UUID.randomUUID(), null, type, "test", "test", now.minusSeconds(5), now,
                type.name(), null, type.name(), now);
    }
}
