package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.CriticVerdict;
import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceGateResult;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class EvidenceGate {

    private final Map<CauseCode, Rule> rules = rules();

    public EvidenceGateResult evaluate(DiagnosisProposal proposal, DiagnosisCritique critique, List<Evidence> evidence) {
        Map<UUID, Evidence> byId = evidence.stream().collect(Collectors.toMap(Evidence::evidenceId, value -> value,
                (first, second) -> second));
        List<String> reasons = new ArrayList<>();
        if (!byId.keySet().containsAll(proposal.supportingEvidenceIds()) || !byId.keySet().containsAll(proposal.counterEvidenceIds())) {
            reasons.add("Proposal references evidence outside this incident");
            return result(DiagnosisStatus.INCONCLUSIVE, CauseCode.UNKNOWN, List.of(), List.of(), List.of(), reasons,
                    "EvidenceGate rejected an out-of-scope Evidence reference");
        }
        if (critique == null) {
            reasons.add("Independent Critic result is missing");
            return result(DiagnosisStatus.INCONCLUSIVE, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), proposal.missingEvidenceTypes(), reasons, "Diagnosis cannot be gated without Critic review");
        }
        if (critique.verdict() == CriticVerdict.REJECT) {
            return result(DiagnosisStatus.CONTRADICTED, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), proposal.missingEvidenceTypes(), List.of("Critic rejected the diagnosis proposal"), critique.summary());
        }
        if (critique.verdict() != CriticVerdict.PASS) {
            return result(DiagnosisStatus.INSUFFICIENT, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), missingFromCritique(proposal, critique), List.of("Critic requires revision or follow-up"), critique.summary());
        }
        if (proposal.status() != com.astrayzjt.faultpilot.common.domain.ProposalStatus.READY_FOR_REVIEW || proposal.primaryCause() == CauseCode.UNKNOWN) {
            return result(DiagnosisStatus.INSUFFICIENT, CauseCode.UNKNOWN, proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), proposal.missingEvidenceTypes(), List.of("Diagnosis proposal is not ready for review"), proposal.causalSummary());
        }
        Rule rule = rules.get(proposal.primaryCause());
        if (rule == null) {
            return result(DiagnosisStatus.INSUFFICIENT, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), List.of(), List.of("No EvidenceGate profile exists for the proposed cause"), "Unsupported cause profile");
        }
        Set<EvidenceType> latestTypes = latestTypes(evidence);
        Set<EvidenceType> supportingTypes = proposal.supportingEvidenceIds().stream().map(byId::get)
                .filter(java.util.Objects::nonNull).map(Evidence::type).collect(Collectors.toSet());
        if (supportingTypes.stream().noneMatch(rule.signalTypes()::contains)) {
            List<EvidenceType> missing = rule.signalTypes().stream().filter(type -> !supportingTypes.contains(type)).toList();
            return result(DiagnosisStatus.INSUFFICIENT, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), missing, List.of("Proposal did not cite every required signal"), "Required signal evidence was not cited");
        }
        Set<EvidenceType> activeCounters = rule.counterTypes().stream().filter(latestTypes::contains).collect(Collectors.toSet());
        if (!activeCounters.isEmpty()) {
            return result(DiagnosisStatus.CONTRADICTED, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), List.of(), List.of("Active counter evidence: " + activeCounters), "The latest observation contradicts the proposed cause");
        }
        Set<EvidenceType> corroboration = rule.corroborationTypes();
        if (corroboration.stream().noneMatch(supportingTypes::contains)) {
            return result(DiagnosisStatus.SUPPORTED, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                    proposal.counterEvidenceIds(), corroboration.stream().toList(), List.of(),
                    "The primary signal is supported, but an independent corroborating check is still missing");
        }
        return result(DiagnosisStatus.CONFIRMED, proposal.primaryCause(), proposal.supportingEvidenceIds(),
                proposal.counterEvidenceIds(), List.of(), List.of(), rule.summary());
    }

    public DiagnosisDecision toDecision(EvidenceGateResult result, List<CauseCode> contributingFactors) {
        return new DiagnosisDecision(result.status(), result.primaryCause(), contributingFactors == null ? List.of() : contributingFactors,
                result.acceptedSupportingEvidenceIds(), result.acceptedCounterEvidenceIds(), result.missingEvidenceTypes(), result.summary());
    }

    private List<EvidenceType> missingFromCritique(DiagnosisProposal proposal, DiagnosisCritique critique) {
        LinkedHashSet<EvidenceType> values = new LinkedHashSet<>(proposal.missingEvidenceTypes());
        critique.issues().forEach(issue -> values.addAll(issue.missingEvidenceTypes()));
        return List.copyOf(values);
    }

    private Set<EvidenceType> latestTypes(List<Evidence> evidence) {
        EnumMap<EvidenceType, Evidence> latest = new EnumMap<>(EvidenceType.class);
        evidence.forEach(item -> latest.put(item.type(), item));
        return Set.copyOf(latest.keySet());
    }

    private EvidenceGateResult result(DiagnosisStatus status, CauseCode cause, List<UUID> supporting,
                                      List<UUID> counter, List<EvidenceType> missing, List<String> reasons, String summary) {
        return new EvidenceGateResult(status, cause, supporting, counter, missing, reasons, summary);
    }

    private Map<CauseCode, Rule> rules() {
        EnumMap<CauseCode, Rule> values = new EnumMap<>(CauseCode.class);
        values.put(CauseCode.JVM_CPU_HOTSPOT, new Rule(
                Set.of(EvidenceType.PROCESS_CPU_HIGH),
                Set.of(EvidenceType.REPEATED_RUNNABLE_STACK, EvidenceType.CPU_HOT_METHOD_FOUND),
                Set.of(EvidenceType.PROCESS_CPU_NORMAL), "JVM CPU hotspot is supported by process and hot-method evidence"));
        values.put(CauseCode.JVM_THREAD_POOL_EXHAUSTED, new Rule(
                Set.of(EvidenceType.THREAD_POOL_ACTIVE_AT_MAX, EvidenceType.THREAD_POOL_QUEUE_GROWING),
                Set.of(EvidenceType.BLOCKING_TASK_FOUND), Set.of(EvidenceType.THREAD_POOL_NORMAL), "JVM worker pool saturation has a blocking-task explanation"));
        values.put(CauseCode.DB_SLOW_QUERY, new Rule(
                Set.of(EvidenceType.SLOW_SQL_FOUND),
                Set.of(EvidenceType.API_AND_SQL_TIME_CORRELATED, EvidenceType.ABNORMAL_EXECUTION_PLAN), Set.of(), "Slow SQL is correlated with the incident latency"));
        values.put(CauseCode.DB_POOL_EXHAUSTED, new Rule(
                Set.of(EvidenceType.DB_POOL_PENDING_HIGH, EvidenceType.DB_POOL_ACTIVE_AT_MAX),
                Set.of(EvidenceType.CONNECTION_HOLDING_QUERY_FOUND), Set.of(), "Database pool pressure has a holding query explanation"));
        values.put(CauseCode.DEPENDENCY_TIMEOUT, new Rule(
                Set.of(EvidenceType.DOWNSTREAM_LATENCY_HIGH), Set.of(EvidenceType.SLOW_CHILD_SPAN_FOUND), Set.of(), "Downstream latency is correlated by a child span"));
        values.put(CauseCode.REDIS_SERVER_LATENCY, new Rule(
                Set.of(EvidenceType.REDIS_COMMAND_LATENCY_HIGH),
                Set.of(EvidenceType.REDIS_SLOW_COMMAND_FOUND, EvidenceType.REDIS_TRACE_LATENCY_CORRELATED),
                Set.of(EvidenceType.REDIS_COMMAND_LATENCY_NORMAL), "Redis command latency is supported by server or trace evidence"));
        values.put(CauseCode.REDIS_CLIENT_POOL_EXHAUSTED, new Rule(
                Set.of(EvidenceType.REDIS_CLIENT_POOL_PENDING_HIGH), Set.of(EvidenceType.REDIS_COMMAND_LATENCY_NORMAL),
                Set.of(EvidenceType.REDIS_CLIENT_POOL_NORMAL), "Redis client pool wait pressure is isolated from server latency"));
        return Map.copyOf(values);
    }

    private record Rule(Set<EvidenceType> signalTypes, Set<EvidenceType> corroborationTypes,
                        Set<EvidenceType> counterTypes, String summary) {
    }
}
