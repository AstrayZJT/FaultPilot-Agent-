package com.astrayzjt.faultpilot.agent.distributed.persistence;

import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvestigationRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public InvestigationRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(InvestigationRun run) {
        jdbcTemplate.update("INSERT INTO incident_investigation_run " +
                        "(id,incident_id,capability_snapshot_id,status,baseline_status,restart_reason,started_at,completed_at,version) " +
                        "VALUES (?,?,?,?,?,?,?,?,?)", run.runId(), run.incidentId(), run.capabilitySnapshotId(),
                run.status().name(), run.baselineStatus().name(), run.restartReason(), Timestamp.from(run.startedAt()),
                timestamp(run.completedAt()), run.version());
    }

    public Optional<InvestigationRun> find(UUID runId) {
        return query("SELECT * FROM incident_investigation_run WHERE id=?", runId).stream().findFirst();
    }

    public Optional<InvestigationRun> findActiveByIncident(UUID incidentId) {
        return query("SELECT * FROM incident_investigation_run WHERE incident_id=? " +
                "AND status IN ('PENDING','RUNNING') ORDER BY started_at DESC LIMIT 1", incidentId).stream().findFirst();
    }

    public List<InvestigationRun> findRecoverable() {
        return query("SELECT * FROM incident_investigation_run WHERE status IN ('PENDING','RUNNING') " +
                "ORDER BY started_at");
    }

    public boolean transition(UUID runId, InvestigationRunStatus expected, InvestigationRunStatus target,
                              long version, String restartReason, Instant completedAt) {
        if (!canTransition(expected, target)) {
            throw new IllegalArgumentException("Invalid investigation run transition: " + expected + " -> " + target);
        }
        if (target.terminal() != (completedAt != null)) {
            throw new IllegalArgumentException("Terminal run transition must provide completedAt only for terminal state");
        }
        return jdbcTemplate.update("UPDATE incident_investigation_run SET status=?,restart_reason=?,completed_at=?," +
                        "version=version+1 WHERE id=? AND status=? AND version=?", target.name(), restartReason,
                timestamp(completedAt), runId, expected.name(), version) == 1;
    }

    public boolean transitionBaseline(UUID runId, BaselineStatus expected, BaselineStatus target, long version) {
        if (!canTransition(expected, target)) {
            throw new IllegalArgumentException("Invalid baseline transition: " + expected + " -> " + target);
        }
        return jdbcTemplate.update("UPDATE incident_investigation_run SET baseline_status=?,version=version+1 " +
                        "WHERE id=? AND baseline_status=? AND version=?", target.name(), runId, expected.name(), version) == 1;
    }

    private List<InvestigationRun> query(String sql, Object... arguments) {
        return jdbcTemplate.query(sql, (rs, row) -> new InvestigationRun(
                rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getObject("capability_snapshot_id", UUID.class),
                InvestigationRunStatus.valueOf(rs.getString("status")),
                BaselineStatus.valueOf(rs.getString("baseline_status")), rs.getString("restart_reason"),
                rs.getTimestamp("started_at").toInstant(), instant(rs.getTimestamp("completed_at")),
                rs.getLong("version")), arguments);
    }

    private boolean canTransition(InvestigationRunStatus from, InvestigationRunStatus to) {
        if (from == null || to == null || from == to || from.terminal()) {
            return false;
        }
        return switch (from) {
            case PENDING -> to == InvestigationRunStatus.RUNNING || to == InvestigationRunStatus.FAILED
                    || to == InvestigationRunStatus.CANCELED || to == InvestigationRunStatus.STALE;
            case RUNNING -> to.terminal();
            case COMPLETED, INCONCLUSIVE, FAILED, CANCELED, STALE -> false;
        };
    }

    private boolean canTransition(BaselineStatus from, BaselineStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return switch (from) {
            case PENDING -> to == BaselineStatus.RUNNING || to == BaselineStatus.FAILED || to == BaselineStatus.STALE;
            case RUNNING -> to == BaselineStatus.COMPLETED || to == BaselineStatus.FAILED || to == BaselineStatus.STALE;
            case COMPLETED, FAILED -> to == BaselineStatus.STALE;
            case STALE -> false;
        };
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
