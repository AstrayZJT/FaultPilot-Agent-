package com.astrayzjt.faultpilot.agent.distributed.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationIdentityTest {

    @Test
    void normalizesObjectiveAndBuildsStableRunScopedIdempotencyKey() {
        UUID runId = UUID.randomUUID();

        String first = DelegationIdentity.idempotencyKey(runId, 1, "jvm-agent",
                "  Determine   whether JVM threads are blocked.  ");
        String repeated = DelegationIdentity.idempotencyKey(runId, 1, "JVM-AGENT",
                "determine whether jvm threads are blocked.");
        String nextRound = DelegationIdentity.idempotencyKey(runId, 2, "jvm-agent",
                "determine whether jvm threads are blocked.");

        assertThat(first).isEqualTo(repeated);
        assertThat(first).startsWith(runId + ":1:jvm-agent:");
        assertThat(first.substring(first.lastIndexOf(':') + 1)).hasSize(64);
        assertThat(nextRound).isNotEqualTo(first);
    }

    @Test
    void rejectsMissingObjectiveAndRunIdentity() {
        assertThatThrownBy(() -> DelegationIdentity.objectiveHash("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DelegationIdentity.idempotencyKey(null, 1, "jvm-agent", "check cpu"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
