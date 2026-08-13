package com.astrayzjt.faultpilot.agent.distributed.persistence;

import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedRunRepositoryTest {

    @Test
    void runTransitionUsesExpectedStateAndVersion() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        InvestigationRunRepository repository = new InvestigationRunRepository(jdbcTemplate);
        UUID runId = UUID.randomUUID();

        boolean updated = repository.transition(runId, InvestigationRunStatus.PENDING,
                InvestigationRunStatus.RUNNING, 4, null, null);

        assertThat(updated).isTrue();
        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        assertThatThrownBy(() -> repository.transition(runId, InvestigationRunStatus.COMPLETED,
                InvestigationRunStatus.RUNNING, 5, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid investigation run transition");
        assertThatThrownBy(() -> repository.transition(runId, InvestigationRunStatus.RUNNING,
                InvestigationRunStatus.COMPLETED, 5, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completedAt");
    }

    @Test
    void baselineTransitionRejectsReopeningStaleData() {
        InvestigationRunRepository repository = new InvestigationRunRepository(mock(JdbcTemplate.class));

        assertThatThrownBy(() -> repository.transitionBaseline(UUID.randomUUID(), BaselineStatus.STALE,
                BaselineStatus.RUNNING, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid baseline transition");
    }

    @Test
    void delegationCompletionRequiresMatchingTerminalArtifact() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        AgentDelegationRepository repository = new AgentDelegationRepository(jdbcTemplate, new ObjectMapper());
        UUID taskId = UUID.randomUUID();
        EvidenceReferenceArtifact artifact = new EvidenceReferenceArtifact(
                EvidenceReferenceArtifact.SCHEMA_VERSION, taskId, "jvm-agent", "jvm-1.0.0",
                DelegationStatus.COMPLETED, List.of(UUID.randomUUID()), 2);

        boolean updated = repository.transition(taskId, DelegationStatus.RUNNING, DelegationStatus.COMPLETED,
                2, "remote-1", artifact, null, null, Instant.now());

        assertThat(updated).isTrue();
        assertThatThrownBy(() -> repository.transition(taskId, DelegationStatus.COMPLETED,
                DelegationStatus.RUNNING, 3, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid delegation transition");
        assertThatThrownBy(() -> repository.transition(UUID.randomUUID(), DelegationStatus.RUNNING,
                DelegationStatus.COMPLETED, 2, "remote-1", artifact, null, null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }
}
