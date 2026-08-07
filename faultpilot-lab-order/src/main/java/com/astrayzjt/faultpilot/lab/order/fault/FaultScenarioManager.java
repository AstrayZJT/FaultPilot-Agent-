package com.astrayzjt.faultpilot.lab.order.fault;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Service
public class FaultScenarioManager {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(2);
    private static final Duration MAX_TTL = Duration.ofMinutes(5);
    private static final int BLOCKED_POOL_SIZE = 4;
    private static final int BLOCKED_TASK_COUNT = 24;
    private static final int REDIS_CLIENT_POOL_SIZE = 4;
    private static final long REDIS_LATENCY_MILLIS = 450;

    private final JdbcTemplate jdbcTemplate;
    private final HikariDataSource dataSource;
    private final Map<ScenarioCode, ActiveFault> activeFaults = new ConcurrentHashMap<>();
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "lab-fault-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean slowSqlEnabled = new AtomicBoolean();
    private final ThreadPoolExecutor exhaustedPool;
    private final Semaphore redisClientPermits = new Semaphore(REDIS_CLIENT_POOL_SIZE, true);
    private final AtomicInteger redisClientActive = new AtomicInteger();
    private final AtomicInteger redisClientPending = new AtomicInteger();
    private volatile CountDownLatch blockedTasks = new CountDownLatch(1);
    private volatile CountDownLatch heldConnections = new CountDownLatch(1);
    private volatile CountDownLatch redisClientBlockers = new CountDownLatch(1);
    private final List<Connection> borrowedConnections = new ArrayList<>();

    public FaultScenarioManager(JdbcTemplate jdbcTemplate, HikariDataSource dataSource, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.exhaustedPool = new ThreadPoolExecutor(
                BLOCKED_POOL_SIZE, BLOCKED_POOL_SIZE, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BLOCKED_TASK_COUNT), r -> {
            Thread thread = new Thread(r, "lab-blocked-worker");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorServiceMetrics.monitor(meterRegistry, exhaustedPool, "labBlockedExecutor");
        Gauge.builder("faultpilot.redis.client.pool.active", redisClientActive, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("faultpilot.redis.client.pool.pending", redisClientPending, AtomicInteger::get).register(meterRegistry);
        Gauge.builder("faultpilot.redis.client.pool.max", () -> REDIS_CLIENT_POOL_SIZE).register(meterRegistry);
    }

    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverStaleRuns() {
        // A restart releases every in-memory fault, so no persisted ACTIVE run can remain actionable.
        jdbcTemplate.update("UPDATE lab_scenario_run SET status='RECOVERED', recovered_at=CURRENT_TIMESTAMP " +
                "WHERE status='ACTIVE' AND target_service='order-service'");
    }

    public synchronized ScenarioRun inject(String rawCode, Long ttlSeconds, String startedBy) {
        ScenarioCode code = parseCode(rawCode);
        ActiveFault existing = activeFaults.get(code);
        if (existing != null && existing.run.expiresAt().isAfter(Instant.now())) {
            return existing.run;
        }

        Instant now = Instant.now();
        Duration ttl = boundedTtl(ttlSeconds);
        ScenarioRun run = new ScenarioRun(UUID.randomUUID(), code, "order-service", "ACTIVE", now,
                now.plus(ttl), null, StringUtils.hasText(startedBy) ? startedBy : "lab", null);
        jdbcTemplate.update("INSERT INTO lab_scenario_run " +
                        "(id, scenario_code, target_service, status, injected_at, expires_at, started_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                run.scenarioRunId(), code.name(), run.targetService(), run.status(), java.sql.Timestamp.from(run.injectedAt()),
                java.sql.Timestamp.from(run.expiresAt()), run.startedBy());
        ActiveFault active = new ActiveFault(run);
        try {
            activate(code, active);
            activeFaults.put(code, active);
            return run;
        } catch (RuntimeException exception) {
            deactivate(code, active);
            jdbcTemplate.update("UPDATE lab_scenario_run SET status='FAILED', recovered_at=CURRENT_TIMESTAMP, " +
                            "error_message=?, version=version+1 WHERE id=?",
                    activationFailureMessage(exception), run.scenarioRunId());
            throw exception;
        }
    }

    public synchronized ScenarioRun recover(UUID scenarioRunId) {
        ActiveFault active = activeFaults.values().stream()
                .filter(value -> value.run.scenarioRunId().equals(scenarioRunId))
                .findFirst().orElse(null);
        if (active == null) {
            return jdbcTemplate.query("SELECT id, scenario_code, target_service, status, injected_at, expires_at, " +
                            "recovered_at, started_by, error_message FROM lab_scenario_run WHERE id = ?",
                    rs -> rs.next() ? mapRun(rs) : null, scenarioRunId);
        }
        deactivate(active.run.scenarioCode(), active);
        activeFaults.remove(active.run.scenarioCode());
        ScenarioRun recovered = new ScenarioRun(active.run.scenarioRunId(), active.run.scenarioCode(),
                active.run.targetService(), "RECOVERED", active.run.injectedAt(), active.run.expiresAt(),
                Instant.now(), active.run.startedBy(), active.run.errorMessage());
        jdbcTemplate.update("UPDATE lab_scenario_run SET status='RECOVERED', recovered_at=?, version=version+1 WHERE id=?",
                java.sql.Timestamp.from(recovered.recoveredAt()), scenarioRunId);
        return recovered;
    }

    public synchronized ScenarioRun recoverActive(String rawCode) {
        ScenarioCode code = parseCode(rawCode);
        ActiveFault active = activeFaults.get(code);
        return active == null ? null : recover(active.run.scenarioRunId());
    }

    public List<ScenarioRun> listRuns() {
        return jdbcTemplate.query("SELECT id, scenario_code, target_service, status, injected_at, expires_at, " +
                        "recovered_at, started_by, error_message FROM lab_scenario_run " +
                        "WHERE target_service='order-service' ORDER BY injected_at DESC LIMIT 50",
                (rs, rowNum) -> mapRun(rs));
    }

    public boolean isActive(ScenarioCode code) {
        ActiveFault active = activeFaults.get(code);
        return active != null && active.run.expiresAt().isAfter(Instant.now());
    }

    public boolean isSlowSqlEnabled() {
        return slowSqlEnabled.get();
    }

    public int blockedQueueSize() {
        return exhaustedPool.getQueue().size();
    }

    public int blockedActiveCount() {
        return exhaustedPool.getActiveCount();
    }

    public int redisClientPoolActiveCount() {
        return redisClientActive.get();
    }

    public int redisClientPoolPendingCount() {
        return redisClientPending.get();
    }

    /**
     * Lab-only admission control makes a cache client pool fault observable without granting a diagnostic tool write access.
     */
    public <T> T executeRedisOperation(Supplier<T> operation) {
        boolean acquired = false;
        boolean pending = false;
        try {
            if (!redisClientPermits.tryAcquire()) {
                pending = true;
                redisClientPending.incrementAndGet();
                if (!redisClientPermits.tryAcquire(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Lab Redis client pool wait timed out");
                }
            }
            acquired = true;
            redisClientActive.incrementAndGet();
            if (isActive(ScenarioCode.REDIS_LATENCY)) {
                sleep(REDIS_LATENCY_MILLIS);
            }
            return operation.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a lab Redis client permit", exception);
        } finally {
            if (acquired) {
                redisClientActive.decrementAndGet();
                redisClientPermits.release();
            }
            if (pending) {
                redisClientPending.decrementAndGet();
            }
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public synchronized void recoverExpired() {
        Instant now = Instant.now();
        activeFaults.values().stream()
                .filter(active -> !active.run.expiresAt().isAfter(now))
                .map(active -> active.run.scenarioRunId())
                .toList()
                .forEach(this::recover);
    }

    private void activate(ScenarioCode code, ActiveFault active) {
        switch (code) {
            case CPU_HOTSPOT -> startCpuHotspot(active);
            case THREAD_POOL_EXHAUSTED -> startBlockedTasks(active);
            case SLOW_SQL -> slowSqlEnabled.set(true);
            case DB_POOL_EXHAUSTED -> startHeldConnections(active);
            case REDIS_LATENCY -> { }
            case REDIS_CLIENT_POOL_EXHAUSTED -> startRedisClientPoolExhausted(active);
        }
    }

    private void deactivate(ScenarioCode code, ActiveFault active) {
        active.stopped.set(true);
        switch (code) {
            case CPU_HOTSPOT -> { }
            case THREAD_POOL_EXHAUSTED -> {
                blockedTasks.countDown();
                exhaustedPool.getQueue().clear();
            }
            case SLOW_SQL -> slowSqlEnabled.set(false);
            case DB_POOL_EXHAUSTED -> {
                heldConnections.countDown();
                synchronized (borrowedConnections) {
                    borrowedConnections.forEach(this::closeQuietly);
                    borrowedConnections.clear();
                }
            }
            case REDIS_LATENCY -> { }
            case REDIS_CLIENT_POOL_EXHAUSTED -> redisClientBlockers.countDown();
        }
    }

    private void startCpuHotspot(ActiveFault active) {
        for (int i = 0; i < 2; i++) {
            workerExecutor.submit(() -> {
                double value = 0;
                while (!active.stopped.get()) {
                    for (int j = 0; j < 100_000; j++) {
                        value += Math.sin(j) * Math.cos(value);
                    }
                    if (value == Double.MAX_VALUE) {
                        break;
                    }
                }
            });
        }
    }

    private void startBlockedTasks(ActiveFault active) {
        blockedTasks = new CountDownLatch(1);
        for (int i = 0; i < BLOCKED_TASK_COUNT; i++) {
            exhaustedPool.execute(() -> {
                try {
                    blockedTasks.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private void startHeldConnections(ActiveFault active) {
        heldConnections = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            workerExecutor.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    synchronized (borrowedConnections) {
                        borrowedConnections.add(connection);
                    }
                    heldConnections.await();
                } catch (Exception ignored) {
                    // The scenario is best-effort and the diagnostic signal is the pool pressure.
                }
            });
        }
    }

    private void startRedisClientPoolExhausted(ActiveFault active) {
        redisClientBlockers = new CountDownLatch(1);
        CountDownLatch acquired = new CountDownLatch(REDIS_CLIENT_POOL_SIZE);
        for (int i = 0; i < REDIS_CLIENT_POOL_SIZE; i++) {
            workerExecutor.submit(() -> {
                boolean permit = false;
                try {
                    permit = redisClientPermits.tryAcquire(2, TimeUnit.SECONDS);
                    if (!permit) {
                        return;
                    }
                    redisClientActive.incrementAndGet();
                    acquired.countDown();
                    redisClientBlockers.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (permit) {
                        redisClientActive.decrementAndGet();
                        redisClientPermits.release();
                    }
                }
            });
        }
        try {
            if (!acquired.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Lab Redis client pool could not acquire all permits");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while activating lab Redis client pool fault", exception);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ScenarioCode parseCode(String rawCode) {
        try {
            return ScenarioCode.valueOf(rawCode.trim().toUpperCase());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unsupported scenario code: " + rawCode);
        }
    }

    private Duration boundedTtl(Long ttlSeconds) {
        long seconds = ttlSeconds == null ? DEFAULT_TTL.toSeconds() : ttlSeconds;
        return Duration.ofSeconds(Math.max(10, Math.min(MAX_TTL.toSeconds(), seconds)));
    }

    private String activationFailureMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.substring(0, Math.min(500, message.length()));
    }

    private ScenarioRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScenarioRun(rs.getObject("id", UUID.class),
                ScenarioCode.valueOf(rs.getString("scenario_code")), rs.getString("target_service"),
                rs.getString("status"), rs.getTimestamp("injected_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("recovered_at") == null ? null : rs.getTimestamp("recovered_at").toInstant(),
                rs.getString("started_by"), rs.getString("error_message"));
    }

    private void closeQuietly(Connection connection) {
        try {
            connection.close();
        } catch (Exception ignored) {
        }
    }

    @PreDestroy
    public void shutdown() {
        List<UUID> activeRunIds = activeFaults.values().stream().map(active -> active.run.scenarioRunId()).toList();
        activeRunIds.forEach(this::recover);
        workerExecutor.shutdownNow();
        exhaustedPool.shutdownNow();
    }

    private static final class ActiveFault {
        private final ScenarioRun run;
        private final AtomicBoolean stopped = new AtomicBoolean();

        private ActiveFault(ScenarioRun run) {
            this.run = run;
        }
    }
}
