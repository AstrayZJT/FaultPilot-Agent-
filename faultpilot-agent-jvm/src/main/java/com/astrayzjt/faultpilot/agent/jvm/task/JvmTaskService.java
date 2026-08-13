package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.core.task.TaskRejectedException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;

@Service
public final class JvmTaskService implements ApplicationRunner {

    private final JvmTaskRepository repository;
    private final JvmInvestigationExecutor investigation;
    private final JvmAgentProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final ConcurrentHashMap<UUID, FutureTask<Void>> inFlight = new ConcurrentHashMap<>();

    public JvmTaskService(JvmTaskRepository repository, JvmInvestigationExecutor investigation,
                          JvmAgentProperties properties, ThreadPoolTaskExecutor jvmAgentTaskExecutor) {
        this.repository = repository;
        this.investigation = investigation;
        this.properties = properties;
        this.executor = jvmAgentTaskExecutor;
    }

    public A2aTaskSnapshot submit(DelegationRequest request) {
        if (!properties.getCapabilityVersion().equals(request.capabilityVersion())) {
            throw new IllegalArgumentException("Delegation capabilityVersion does not match this JVM Agent");
        }
        properties.requireService(request.incident().serviceName());
        JvmTaskRepository.CreateResult created = repository.createOrGet(request);
        if (!created.task().status().terminal()) {
            schedule(created.task().remoteTaskId());
        }
        return repository.find(created.task().remoteTaskId()).orElseThrow().snapshot(properties);
    }

    public Optional<A2aTaskSnapshot> query(UUID remoteTaskId) {
        return repository.find(remoteTaskId).map(task -> task.snapshot(properties));
    }

    public boolean cancel(UUID remoteTaskId) {
        Optional<JvmTaskRecord> existing = repository.find(remoteTaskId);
        if (existing.isEmpty()) {
            return false;
        }
        repository.cancel(remoteTaskId);
        FutureTask<Void> task = inFlight.get(remoteTaskId);
        if (task != null) {
            task.cancel(true);
        }
        return true;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.findActive().forEach(task -> schedule(task.remoteTaskId()));
    }

    private synchronized void schedule(UUID remoteTaskId) {
        if (inFlight.containsKey(remoteTaskId)) {
            return;
        }
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(remoteTaskId);
            return null;
        }) {
            @Override
            protected void done() {
                inFlight.remove(remoteTaskId, this);
            }
        };
        inFlight.put(remoteTaskId, task);
        try {
            executor.execute(task);
        } catch (TaskRejectedException rejected) {
            inFlight.remove(remoteTaskId, task);
            JvmTaskProgress progress = repository.progress(remoteTaskId);
            repository.complete(remoteTaskId, new TaskOutcome(TaskStatus.FAILED, progress.evidenceIds(),
                    progress.stepsUsed(), "JVM_AGENT_BUSY", "JVM Agent task queue is full"));
        }
    }

    private void execute(UUID remoteTaskId) {
        JvmTaskRecord task = repository.find(remoteTaskId).orElseThrow();
        if (!repository.markRunning(remoteTaskId)) {
            return;
        }
        try {
            TaskOutcome outcome = investigation.execute(task.request(), repository.progress(remoteTaskId),
                    () -> canceled(remoteTaskId), new RepositoryProgressRecorder(remoteTaskId));
            repository.complete(remoteTaskId, outcome);
        } catch (RuntimeException failure) {
            JvmTaskProgress progress = repository.progress(remoteTaskId);
            repository.complete(remoteTaskId, new TaskOutcome(TaskStatus.FAILED, progress.evidenceIds(),
                    progress.stepsUsed(), failure.getClass().getSimpleName(), safeMessage(failure)));
        }
    }

    private boolean canceled(UUID remoteTaskId) {
        return Thread.currentThread().isInterrupted() || repository.find(remoteTaskId)
                .map(task -> task.status() == TaskStatus.CANCELED).orElse(true);
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }

    private final class RepositoryProgressRecorder implements JvmInvestigationProgressRecorder {
        private final UUID remoteTaskId;

        private RepositoryProgressRecorder(UUID remoteTaskId) {
            this.remoteTaskId = remoteTaskId;
        }

        @Override
        public void selectSkill(String skillName) {
            if (!repository.selectSkill(remoteTaskId, skillName)) {
                throw new IllegalStateException("Cannot persist selected JVM Skill");
            }
        }

        @Override
        public JvmToolCallRecord reserveTool(String toolName) {
            return repository.reserveTool(remoteTaskId, toolName);
        }

        @Override
        public void completeTool(JvmToolCallRecord call, UUID evidenceId) {
            if (!remoteTaskId.equals(call.remoteTaskId())
                    || !repository.completeTool(remoteTaskId, call.toolCallId(), evidenceId)) {
                throw new IllegalStateException("Cannot persist completed JVM Tool call");
            }
        }
    }
}
