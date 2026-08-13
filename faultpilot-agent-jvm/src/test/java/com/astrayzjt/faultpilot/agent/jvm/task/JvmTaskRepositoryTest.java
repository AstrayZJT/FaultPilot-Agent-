package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JvmTaskRepositoryTest {

    private JvmTaskRepository repository;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:jvm-agent-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new JvmTaskRepository(new JdbcTemplate(dataSource),
                JsonMapper.builder().findAndAddModules().build());
    }

    @Test
    void createsOnceAndReturnsSameTaskForIdenticalIdempotentRequest() {
        DelegationRequest request = request("objective");

        JvmTaskRepository.CreateResult first = repository.createOrGet(request);
        JvmTaskRepository.CreateResult duplicate = repository.createOrGet(request);

        assertThat(first.created()).isTrue();
        assertThat(duplicate.created()).isFalse();
        assertThat(duplicate.task().remoteTaskId()).isEqualTo(first.task().remoteTaskId());
        assertThat(repository.findActive()).singleElement()
                .extracting(JvmTaskRecord::status).isEqualTo(TaskStatus.SUBMITTED);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentRequest() {
        DelegationRequest original = request("objective");
        repository.createOrGet(original);
        DelegationRequest changed = new DelegationRequest(original.schemaVersion(), original.taskId(),
                original.idempotencyKey(), original.capabilityVersion(), original.incident(),
                "different objective", original.availableEvidenceIds(), original.limits());

        assertThatThrownBy(() -> repository.createOrGet(changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different delegation");
    }

    @Test
    void refreshesDeadlineAndEvidenceForSameStableIdempotentTask() {
        DelegationRequest original = request("objective");
        JvmTaskRecord first = repository.createOrGet(original).task();
        UUID additionalEvidence = UUID.randomUUID();
        DelegationRequest refreshed = new DelegationRequest(original.schemaVersion(), original.taskId(),
                original.idempotencyKey(), original.capabilityVersion(), original.incident(), original.objective(),
                List.of(additionalEvidence), new DelegationRequest.Limits(original.limits().maxSteps(),
                original.limits().deadline().plusSeconds(30)));

        JvmTaskRepository.CreateResult result = repository.createOrGet(refreshed);

        assertThat(result.created()).isFalse();
        assertThat(result.task().remoteTaskId()).isEqualTo(first.remoteTaskId());
        assertThat(result.task().request().limits().deadline()).isEqualTo(refreshed.limits().deadline());
        assertThat(result.task().request().availableEvidenceIds()).containsExactly(additionalEvidence);
    }

    @Test
    void persistsIncrementalEvidenceAndProtectsTerminalState() {
        JvmTaskRecord task = repository.createOrGet(request("objective")).task();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertThat(repository.markRunning(task.remoteTaskId())).isTrue();
        assertThat(repository.appendEvidence(task.remoteTaskId(), first)).isTrue();
        assertThat(repository.appendEvidence(task.remoteTaskId(), first)).isTrue();
        assertThat(repository.complete(task.remoteTaskId(), new TaskOutcome(TaskStatus.COMPLETED,
                List.of(first, second), 2, null, null))).isTrue();

        JvmTaskRecord completed = repository.find(task.remoteTaskId()).orElseThrow();
        assertThat(completed.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(completed.evidenceIds()).containsExactly(first, second);
        assertThat(repository.cancel(task.remoteTaskId())).isFalse();
        assertThat(repository.appendEvidence(task.remoteTaskId(), UUID.randomUUID())).isFalse();
    }

    @Test
    void persistsSelectedSkillAndReservedToolAcrossRestart() {
        JvmTaskRecord task = repository.createOrGet(request("objective")).task();
        repository.markRunning(task.remoteTaskId());

        assertThat(repository.selectSkill(task.remoteTaskId(), "jvm-cpu-hotspot")).isTrue();
        JvmToolCallRecord call = repository.reserveTool(task.remoteTaskId(), "query_prometheus_process_cpu");

        JvmTaskProgress progress = repository.progress(task.remoteTaskId());
        assertThat(progress.selectedSkill()).isEqualTo("jvm-cpu-hotspot");
        assertThat(progress.stepsUsed()).isEqualTo(1);
        assertThat(progress.toolCalls()).singleElement().isEqualTo(call);
        assertThat(call.status()).isEqualTo(ToolCallStatus.RESERVED);
        assertThat(repository.reserveTool(task.remoteTaskId(), call.toolName())).isEqualTo(call);

        UUID evidenceId = UUID.randomUUID();
        assertThat(repository.completeTool(task.remoteTaskId(), call.toolCallId(), evidenceId)).isTrue();
        assertThat(repository.progress(task.remoteTaskId()).toolCalls()).singleElement().satisfies(completed -> {
            assertThat(completed.status()).isEqualTo(ToolCallStatus.COMPLETED);
            assertThat(completed.evidenceId()).isEqualTo(evidenceId);
        });
        assertThat(repository.progress(task.remoteTaskId()).evidenceIds()).containsExactly(evidenceId);
    }

    private DelegationRequest request(String objective) {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "run:1:jvm:hash",
                "1.0.0", new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(),
                "order-service", "CPU high", new TimeRange(now.minusSeconds(60), now)), objective, List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(60)));
    }
}
