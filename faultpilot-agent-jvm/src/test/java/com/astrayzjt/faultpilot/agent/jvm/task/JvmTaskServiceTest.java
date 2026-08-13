package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class JvmTaskServiceTest {

    private JvmTaskRepository repository;
    private JvmAgentProperties properties;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:jvm-service-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        repository = new JvmTaskRepository(new JdbcTemplate(dataSource),
                JsonMapper.builder().findAndAddModules().build());
        properties = new JvmAgentProperties();
        JvmAgentProperties.ServiceTarget target = new JvmAgentProperties.ServiceTarget();
        target.setPrometheusLabels(Map.of("job", "faultpilot-lab-order"));
        properties.setServices(Map.of("order-service", target));
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
    }

    @AfterEach
    void shutDown() {
        executor.shutdown();
    }

    @Test
    void identicalSubmissionsExecuteOnlyOnceAndReturnSameRemoteTask() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        UUID evidenceId = UUID.randomUUID();
        JvmInvestigationExecutor investigation = (task, taskProgress, canceled, progress) -> {
            executions.incrementAndGet();
            progress.selectSkill("jvm-cpu-hotspot");
            JvmToolCallRecord call = progress.reserveTool("query_arthas_hot_threads");
            progress.completeTool(call, evidenceId);
            return new TaskOutcome(TaskStatus.COMPLETED, List.of(evidenceId), 1, null, null);
        };
        JvmTaskService service = new JvmTaskService(repository, investigation, properties, executor);
        DelegationRequest request = request();

        String firstRemoteId = service.submit(request).remoteTaskId();
        awaitTerminal(service, UUID.fromString(firstRemoteId));
        String duplicateRemoteId = service.submit(request).remoteTaskId();

        assertThat(duplicateRemoteId).isEqualTo(firstRemoteId);
        assertThat(executions).hasValue(1);
        assertThat(service.query(UUID.fromString(firstRemoteId))).isPresent().get().satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo(TaskStatus.COMPLETED);
            assertThat(snapshot.artifact().evidenceIds()).containsExactly(evidenceId);
        });
    }

    @Test
    void startupReschedulesPersistedActiveTask() throws Exception {
        DelegationRequest request = request();
        JvmTaskRecord persisted = repository.createOrGet(request).task();
        CountDownLatch called = new CountDownLatch(1);
        JvmInvestigationExecutor investigation = (task, taskProgress, canceled, progress) -> {
            called.countDown();
            return new TaskOutcome(TaskStatus.INSUFFICIENT, List.of(), 0,
                    "NO_EVIDENCE", "No evidence available");
        };
        JvmTaskService service = new JvmTaskService(repository, investigation, properties, executor);

        service.run(null);

        assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
        awaitTerminal(service, persisted.remoteTaskId());
        assertThat(service.query(persisted.remoteTaskId())).isPresent().get()
                .extracting(snapshot -> snapshot.status()).isEqualTo(TaskStatus.INSUFFICIENT);
    }

    @Test
    void marksTaskFailedWhenExecutorRejectsSubmission() {
        ThreadPoolTaskExecutor rejectedExecutor = new ThreadPoolTaskExecutor();
        rejectedExecutor.setCorePoolSize(1);
        rejectedExecutor.setMaxPoolSize(1);
        rejectedExecutor.setQueueCapacity(0);
        rejectedExecutor.initialize();
        rejectedExecutor.shutdown();
        JvmInvestigationExecutor investigation = (task, taskProgress, canceled, progress) -> {
            throw new AssertionError("Rejected task must not execute");
        };
        JvmTaskService service = new JvmTaskService(repository, investigation, properties, rejectedExecutor);

        var snapshot = service.submit(request());

        assertThat(snapshot.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(snapshot.errorCode()).isEqualTo("JVM_AGENT_BUSY");
    }

    private void awaitTerminal(JvmTaskService service, UUID remoteTaskId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (service.query(remoteTaskId).orElseThrow().status().terminal()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("JVM task did not reach a terminal state");
    }

    private DelegationRequest request() {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "run:1:jvm:hash",
                "1.0.0", new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(),
                "order-service", "CPU high", new TimeRange(now.minusSeconds(60), now)), "Find CPU hotspot",
                List.of(), new DelegationRequest.Limits(4, now.plusSeconds(60)));
    }
}
