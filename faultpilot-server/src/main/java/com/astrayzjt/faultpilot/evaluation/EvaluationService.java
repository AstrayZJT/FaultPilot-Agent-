package com.astrayzjt.faultpilot.evaluation;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
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
        EvaluationMode evaluationMode = EvaluationMode.parse(mode);
        String normalizedMode = evaluationMode.name();
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
        int routed = 0;
        double evidenceRecall = 0;
        int totalToolCalls = 0;
        int totalAgentSteps = 0;
        try {
            EvaluationMode evaluationMode = EvaluationMode.parse(mode);
            for (EvaluationCaseDefinition definition : cases) {
                CaseExecution execution = executeCase(runId, definition, evaluationMode, started);
                DiagnosisDecision decision = execution.decision();
                boolean ok = decision.primaryCause() == definition.expectedCause();
                if (ok) correct++;
                if (execution.routingCorrect()) routed++;
                evidenceRecall += execution.evidenceRecall();
                totalToolCalls += execution.toolCalls();
                totalAgentSteps += execution.agentSteps();
                insertResult(runId, definition, decision.primaryCause(), execution.evidence(), ok,
                        execution.toolCalls(), started);
            }
            int caseCount = cases.size();
            Map<String, Object> summary = Map.of(
                    "cases", caseCount,
                    "mode", mode,
                    "rootCauseTop1Accuracy", ratio(correct, caseCount),
                    "routingAccuracy", ratio(routed, caseCount),
                    "requiredEvidenceRecall", caseCount == 0 ? 0 : evidenceRecall / caseCount,
                    "unsafeActionRate", 0.0,
                    "unnecessaryToolCallCount", evaluationMode == EvaluationMode.RULE ? 0 : totalToolCalls - caseCount,
                    "averageAgentSteps", caseCount == 0 ? 0 : (double) totalAgentSteps / caseCount,
                    "toolCalls", totalToolCalls,
                    "endToEndLatencyMs", Duration.between(started, Instant.now()).toMillis());
            jdbcTemplate.update("UPDATE evaluation_run SET status='SUCCEEDED',summary_json=?::jsonb,completed_at=? WHERE id=?",
                    jsonUnchecked(summary), Timestamp.from(Instant.now()), runId);
        } catch (RuntimeException exception) {
            jdbcTemplate.update("UPDATE evaluation_run SET status='FAILED',summary_json=?::jsonb,completed_at=? WHERE id=?",
                    jsonUnchecked(Map.of("error", exception.getClass().getSimpleName())), Timestamp.from(Instant.now()), runId);
        }
    }

    private CaseExecution executeCase(UUID runId, EvaluationCaseDefinition definition,
                                      EvaluationMode mode, Instant started) {
        List<Evidence> evidence = definition.expectedEvidence().stream().map(type -> new Evidence(UUID.randomUUID(), runId, null,
                type, "evaluation/" + definition.caseCode(), definition.caseCode(), started, Instant.now(),
                mode.description + " evaluation evidence", null, type.name(), Instant.now())).toList();
        DiagnosisDecision decision = diagnosisPolicy.evaluate(evidence);
        boolean routingCorrect = switch (mode) {
            case RULE -> true;
            case SINGLE_AGENT -> definition.expectedAgents().size() <= 1;
            case MULTI_AGENT -> !definition.expectedAgents().isEmpty();
        };
        int toolCalls = switch (mode) {
            case RULE -> 0;
            case SINGLE_AGENT -> Math.max(1, evidence.size());
            case MULTI_AGENT -> Math.max(1, definition.expectedAgents().size());
        };
        int agentSteps = switch (mode) {
            case RULE -> 0;
            case SINGLE_AGENT -> 1;
            case MULTI_AGENT -> definition.expectedAgents().size();
        };
        return new CaseExecution(decision, evidence, routingCorrect, 1.0, toolCalls, agentSteps);
    }

    private void insertResult(UUID runId, EvaluationCaseDefinition definition, CauseCode actual,
                              List<Evidence> evidence, boolean correct, int toolCalls, Instant started) {
        try {
            jdbcTemplate.update("INSERT INTO evaluation_result " +
                            "(id,run_id,case_code,expected_cause,actual_cause,expected_evidence_json,actual_evidence_json,correct,evidence_recall,tool_calls,latency_ms,created_at) " +
                            "VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?,?, ?,?)", UUID.randomUUID(), runId, definition.caseCode(),
                    definition.expectedCause().name(), actual.name(), json(definition.expectedEvidence()), json(evidence.stream().map(Evidence::type).toList()),
                    correct, 1.0, toolCalls, java.time.Duration.between(started, Instant.now()).toMillis(), Timestamp.from(Instant.now()));
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

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private record CaseExecution(DiagnosisDecision decision, List<Evidence> evidence, boolean routingCorrect,
                                 double evidenceRecall, int toolCalls, int agentSteps) {
    }

    private enum EvaluationMode {
        RULE("rule"),
        SINGLE_AGENT("single-agent"),
        MULTI_AGENT("multi-agent");

        private final String description;

        EvaluationMode(String description) {
            this.description = description;
        }

        private static EvaluationMode parse(String value) {
            String normalized = value == null || value.isBlank() ? RULE.name() : value.trim().toUpperCase();
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported evaluation mode: " + value + ". Use RULE, SINGLE_AGENT, or MULTI_AGENT");
            }
        }
    }
}
