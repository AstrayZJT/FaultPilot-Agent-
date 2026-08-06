package com.astrayzjt.faultpilot.evaluation;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvaluationService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DiagnosisPolicy diagnosisPolicy;
    private final ThreadPoolTaskExecutor executor;

    private final List<EvaluationCaseDefinition> cases = List.of(
            new EvaluationCaseDefinition("CPU_HOTSPOT", CauseCode.JVM_CPU_HOTSPOT, List.of(EvidenceType.PROCESS_CPU_HIGH), List.of("JVM_AGENT")),
            new EvaluationCaseDefinition("THREAD_POOL_EXHAUSTED", CauseCode.JVM_THREAD_POOL_EXHAUSTED, List.of(EvidenceType.THREAD_POOL_ACTIVE_AT_MAX), List.of("JVM_AGENT")),
            new EvaluationCaseDefinition("SLOW_SQL", CauseCode.DB_SLOW_QUERY, List.of(EvidenceType.SLOW_SQL_FOUND), List.of("JVM_AGENT", "DATABASE_AGENT")),
            new EvaluationCaseDefinition("DB_POOL_EXHAUSTED", CauseCode.DB_POOL_EXHAUSTED, List.of(EvidenceType.DB_POOL_ACTIVE_AT_MAX), List.of("DATABASE_AGENT")),
            new EvaluationCaseDefinition("DEPENDENCY_TIMEOUT", CauseCode.DEPENDENCY_TIMEOUT, List.of(EvidenceType.DOWNSTREAM_LATENCY_HIGH), List.of("DEPENDENCY_AGENT")));

    public EvaluationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, DiagnosisPolicy diagnosisPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.diagnosisPolicy = diagnosisPolicy;
        this.executor = new ThreadPoolTaskExecutor();
        this.executor.setCorePoolSize(1);
        this.executor.setMaxPoolSize(1);
        this.executor.setQueueCapacity(2);
        this.executor.setThreadNamePrefix("faultpilot-evaluation-");
        this.executor.initialize();
    }

    public UUID start(String mode) {
        String normalizedMode = mode == null || mode.isBlank() ? "RULE" : mode.toUpperCase();
        UUID runId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO evaluation_run (id,status,mode,summary_json,created_at) VALUES (?, 'RUNNING', ?, '{}'::jsonb, ?)",
                runId, normalizedMode, Timestamp.from(Instant.now()));
        executor.execute(() -> execute(runId, normalizedMode));
        return runId;
    }

    public Map<String, Object> find(UUID runId) {
        return jdbcTemplate.query("SELECT id,status,mode,summary_json,created_at,completed_at FROM evaluation_run WHERE id=?",
                rs -> rs.next() ? Map.of("evaluationRunId", rs.getObject("id", UUID.class), "status", rs.getString("status"),
                        "mode", rs.getString("mode"), "summary", parse(rs.getString("summary_json")),
                        "createdAt", rs.getTimestamp("created_at").toInstant(), "completedAt",
                        rs.getTimestamp("completed_at") == null ? "" : rs.getTimestamp("completed_at").toInstant()) : Map.of(), runId);
    }

    public List<EvaluationCaseDefinition> definitions() {
        return cases;
    }

    private void execute(UUID runId, String mode) {
        Instant started = Instant.now();
        int correct = 0;
        try {
            for (EvaluationCaseDefinition definition : cases) {
                List<Evidence> evidence = definition.expectedEvidence().stream().map(type -> new Evidence(UUID.randomUUID(), runId, null,
                        type, "evaluation/" + definition.caseCode(), definition.caseCode(), started, Instant.now(), "fixed evaluation evidence", null,
                        type.name(), Instant.now())).toList();
                var decision = diagnosisPolicy.evaluate(evidence);
                boolean ok = decision.primaryCause() == definition.expectedCause();
                if (ok) correct++;
                insertResult(runId, definition, decision.primaryCause(), evidence, ok, started);
            }
            Map<String, Object> summary = Map.of("cases", cases.size(), "correct", correct,
                    "accuracy", cases.isEmpty() ? 0 : (double) correct / cases.size(), "mode", mode);
            jdbcTemplate.update("UPDATE evaluation_run SET status='SUCCEEDED',summary_json=?::jsonb,completed_at=? WHERE id=?",
                    jsonUnchecked(summary), Timestamp.from(Instant.now()), runId);
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE evaluation_run SET status='FAILED',summary_json=?::jsonb,completed_at=? WHERE id=?",
                    jsonUnchecked(Map.of("error", exception.getClass().getSimpleName())), Timestamp.from(Instant.now()), runId);
        }
    }

    private void insertResult(UUID runId, EvaluationCaseDefinition definition, CauseCode actual,
                              List<Evidence> evidence, boolean correct, Instant started) {
        try {
            jdbcTemplate.update("INSERT INTO evaluation_result " +
                            "(id,run_id,case_code,expected_cause,actual_cause,expected_evidence_json,actual_evidence_json,correct,evidence_recall,tool_calls,latency_ms,created_at) " +
                            "VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?, ?,?)", UUID.randomUUID(), runId, definition.caseCode(),
                    definition.expectedCause().name(), actual.name(), json(definition.expectedEvidence()), json(evidence.stream().map(Evidence::type).toList()),
                    correct, 1.0, 0, java.time.Duration.between(started, Instant.now()).toMillis(), Timestamp.from(Instant.now()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize evaluation result", exception);
        }
    }

    private String json(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }

    private String jsonUnchecked(Object value) {
        try {
            return json(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private Object parse(String value) {
        try {
            return objectMapper.readTree(value == null ? "{}" : value);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }
}
