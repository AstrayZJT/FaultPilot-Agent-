package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

@Component
public class DiagnosisPolicy {

    private final List<Rule> rules = List.of(
            new Rule(CauseCode.JVM_CPU_HOTSPOT, EvidenceType.PROCESS_CPU_HIGH, "JVM CPU hotspot is supported by process CPU evidence"),
            new Rule(CauseCode.JVM_THREAD_POOL_EXHAUSTED, EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, "JVM worker pool is saturated"),
            new Rule(CauseCode.DB_SLOW_QUERY, EvidenceType.SLOW_SQL_FOUND, "Database slow SQL scenario explains the latency"),
            new Rule(CauseCode.DB_POOL_EXHAUSTED, EvidenceType.DB_POOL_ACTIVE_AT_MAX, "Database connection pool is saturated"),
            new Rule(CauseCode.DEPENDENCY_TIMEOUT, EvidenceType.DOWNSTREAM_LATENCY_HIGH, "Downstream latency explains the incident"));

    public DiagnosisDecision evaluate(List<Evidence> evidence) {
        List<Evidence> matches = evidence.stream().filter(evidenceItem -> rules.stream()
                .anyMatch(rule -> rule.evidenceType() == evidenceItem.type())).toList();
        if (matches.isEmpty()) {
            return new DiagnosisDecision(DiagnosisStatus.INSUFFICIENT, CauseCode.UNKNOWN, List.of(), List.of(),
                    List.of(), List.of(EvidenceType.API_LATENCY_REGRESSION), "No catalog rule has enough evidence");
        }
        Evidence selected = matches.get(matches.size() - 1);
        Rule rule = rules.stream().filter(candidate -> candidate.evidenceType() == selected.type()).findFirst().orElseThrow();
        List<UUID> ids = matches.stream().map(Evidence::evidenceId).toList();
        List<CauseCode> factors = rule.cause() == CauseCode.DB_SLOW_QUERY && matches.stream()
                .anyMatch(item -> item.type() == EvidenceType.DB_POOL_ACTIVE_AT_MAX)
                ? List.of(CauseCode.DB_POOL_EXHAUSTED) : List.of();
        return new DiagnosisDecision(DiagnosisStatus.CONFIRMED, rule.cause(), factors, ids, List.of(), List.of(), rule.summary());
    }

    private record Rule(CauseCode cause, EvidenceType evidenceType, String summary) {
    }
}

