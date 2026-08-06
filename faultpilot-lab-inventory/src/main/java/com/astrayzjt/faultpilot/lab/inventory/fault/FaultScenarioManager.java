package com.astrayzjt.faultpilot.lab.inventory.fault;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class FaultScenarioManager {

    private final JdbcTemplate jdbcTemplate;
    private final Map<ScenarioCode, ActiveFault> activeFaults = new ConcurrentHashMap<>();
    private final AtomicBoolean dependencyDelay = new AtomicBoolean();

    public FaultScenarioManager(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public synchronized ScenarioRun inject(String rawCode, Long ttlSeconds, String startedBy) {
        ScenarioCode code;
        try {
            code = ScenarioCode.valueOf(rawCode.trim().toUpperCase());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unsupported scenario code: " + rawCode);
        }
        ActiveFault existing = activeFaults.get(code);
        if (existing != null && existing.run.expiresAt().isAfter(Instant.now())) {
            return existing.run;
        }
        Instant now = Instant.now();
        long seconds = ttlSeconds == null ? 120 : Math.max(10, Math.min(300, ttlSeconds));
        ScenarioRun run = new ScenarioRun(UUID.randomUUID(), code, "inventory-service", "ACTIVE", now,
                now.plusSeconds(seconds), null, StringUtils.hasText(startedBy) ? startedBy : "lab");
        jdbcTemplate.update("INSERT INTO lab_scenario_run " +
                        "(id, scenario_code, target_service, status, injected_at, expires_at, started_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)", run.scenarioRunId(), code.name(), run.targetService(),
                run.status(), java.sql.Timestamp.from(run.injectedAt()), java.sql.Timestamp.from(run.expiresAt()), run.startedBy());
        activeFaults.put(code, new ActiveFault(run));
        dependencyDelay.set(true);
        return run;
    }

    public synchronized ScenarioRun recover(UUID id) {
        ActiveFault active = activeFaults.values().stream()
                .filter(value -> value.run.scenarioRunId().equals(id)).findFirst().orElse(null);
        if (active == null) {
            return jdbcTemplate.query("SELECT id, scenario_code, target_service, status, injected_at, expires_at, " +
                            "recovered_at, started_by FROM lab_scenario_run WHERE id=?",
                    rs -> rs.next() ? mapRun(rs) : null, id);
        }
        activeFaults.remove(active.run.scenarioCode());
        dependencyDelay.set(false);
        ScenarioRun result = new ScenarioRun(active.run.scenarioRunId(), active.run.scenarioCode(),
                active.run.targetService(), "RECOVERED", active.run.injectedAt(), active.run.expiresAt(),
                Instant.now(), active.run.startedBy());
        jdbcTemplate.update("UPDATE lab_scenario_run SET status='RECOVERED', recovered_at=?, version=version+1 WHERE id=?",
                java.sql.Timestamp.from(result.recoveredAt()), id);
        return result;
    }

    public boolean isDependencyDelayEnabled() {
        return dependencyDelay.get();
    }

    public List<ScenarioRun> listRuns() {
        return jdbcTemplate.query("SELECT id, scenario_code, target_service, status, injected_at, expires_at, " +
                        "recovered_at, started_by FROM lab_scenario_run WHERE target_service='inventory-service' " +
                        "ORDER BY injected_at DESC LIMIT 50", (rs, row) -> mapRun(rs));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public synchronized void recoverExpired() {
        activeFaults.values().stream().filter(value -> !value.run.expiresAt().isAfter(Instant.now()))
                .map(value -> value.run.scenarioRunId()).toList().forEach(this::recover);
    }

    private ScenarioRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScenarioRun(rs.getObject("id", UUID.class), ScenarioCode.valueOf(rs.getString("scenario_code")),
                rs.getString("target_service"), rs.getString("status"), rs.getTimestamp("injected_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("recovered_at") == null ? null : rs.getTimestamp("recovered_at").toInstant(),
                rs.getString("started_by"));
    }

    private record ActiveFault(ScenarioRun run) {
    }
}
