package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Local policy boundary around an untrusted Main Agent response. */
@Component
public final class MainAgentDecisionGuard {

    private static final int MAX_PARALLEL_DELEGATIONS = 3;
    private static final Pattern FORBIDDEN_OBJECTIVE = Pattern.compile(
            "(?is)(https?://|jdbc:|authorization\\s*:|bearer\\s+[a-z0-9._-]+|redis-cli|curl\\s+|wget\\s+|" +
                    "thread\\s+--|\\bselect\\s+.+\\s+from\\b|\\binsert\\s+into\\b|" +
                    "\\bupdate\\s+\\w+\\s+set\\b|\\bdelete\\s+from\\b|\\bdrop\\s+(table|database)\\b|" +
                    "\\balter\\s+table\\b)");

    private final CapabilityRegistry capabilities;
    private final Map<CauseCode, EvidenceRule> evidenceRules = evidenceRules();

    public MainAgentDecisionGuard(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public MainAgentDecision validate(MainAgentDecision decision, MainAgentContext context) {
        if (decision == null) {
            throw new IllegalArgumentException("Main Agent returned no decision");
        }
        if (context.nextRound() > context.maxRounds() || Instant.now().isAfter(context.deadline())) {
            return MainAgentDecision.inconclusive("Main Agent round budget exhausted");
        }
        Set<UUID> availableEvidence = new HashSet<>();
        for (Evidence item : context.evidence()) {
                if (item == null || item.evidenceId() == null || item.incidentId() == null) {
                    throw new IllegalArgumentException("Evidence has an invalid identity");
                }
                if (!context.incident().incidentId().equals(item.incidentId())) {
                    throw new IllegalArgumentException("Evidence belongs to another Incident");
                }
                if (!context.run().runId().equals(item.runId())
                        || item.status() != com.astrayzjt.faultpilot.common.domain.EvidenceStatus.ACTIVE) {
                    throw new IllegalArgumentException("Evidence is not ACTIVE in the current investigation Run");
                }
                availableEvidence.add(item.evidenceId());
        }
        if (!availableEvidence.containsAll(decision.evidenceIds())) {
            throw new IllegalArgumentException("Main Agent referenced evidence outside the current Incident");
        }
        if (decision.action() == MainAgentAction.DELEGATE) {
            if (decision.draft() != null) {
                throw new IllegalArgumentException("DELEGATE must not include a diagnosis draft");
            }
            if (decision.delegations().size() > MAX_PARALLEL_DELEGATIONS) {
                throw new IllegalArgumentException("A Main Agent round may contain at most 3 delegations");
            }
            if (context.delegations().size() + decision.delegations().size() > context.maxDelegations()) {
                return MainAgentDecision.inconclusive("Main Agent delegation budget exhausted");
            }
            Set<String> roundKeys = new HashSet<>();
            for (SpecialistDelegation delegation : decision.delegations()) {
                AgentType type = delegation.agentType();
                capabilities.requireAvailable(type);
                if (delegation.objective().length() > 2_000) {
                    throw new IllegalArgumentException("Delegation objective must contain 1-2000 characters");
                }
                if (FORBIDDEN_OBJECTIVE.matcher(delegation.objective()).find()) {
                    throw new IllegalArgumentException(
                            "Delegation objective contains a forbidden URL, credential, query or command");
                }
                String objectiveHash = DelegationIdentity.objectiveHash(delegation.objective());
                String key = type + ":" + objectiveHash;
                if (!roundKeys.add(key)) {
                    throw new IllegalArgumentException("Main Agent repeated a delegation within the same round");
                }
                boolean duplicate = context.delegations().stream().anyMatch(item -> item.agentType() == type
                        && item.objectiveHash().equals(objectiveHash)
                        && item.status() != DelegationStatus.FAILED
                        && item.status() != DelegationStatus.TIMED_OUT);
                if (duplicate) {
                    throw new IllegalArgumentException("Main Agent repeated an already executed delegation");
                }
            }
            return decision;
        }
        if (decision.action() == MainAgentAction.COMPLETE) {
            DiagnosisDraft draft = decision.draft();
            if (draft == null || draft.primaryCause() == CauseCode.UNKNOWN
                    || (draft.status() != DiagnosisStatus.CONFIRMED && draft.status() != DiagnosisStatus.SUPPORTED)
                    || draft.supportingEvidenceIds().isEmpty()) {
                return MainAgentDecision.inconclusive(
                        "Main Agent completion was rejected because it lacked a supported cause and Evidence");
            }
            if (!availableEvidence.containsAll(draft.supportingEvidenceIds())
                    || !availableEvidence.containsAll(draft.counterEvidenceIds())) {
                throw new IllegalArgumentException("Diagnosis draft referenced evidence outside the current Incident");
            }
            return normalizeDiagnosis(decision, context.evidence());
        }
        return MainAgentDecision.inconclusive(decision.reason().isBlank()
                ? "Main Agent could not establish a supported diagnosis" : decision.reason());
    }

    private MainAgentDecision normalizeDiagnosis(MainAgentDecision decision, List<Evidence> evidence) {
        DiagnosisDraft draft = decision.draft();
        EvidenceRule rule = evidenceRules.get(draft.primaryCause());
        if (rule == null) {
            return MainAgentDecision.inconclusive("No local Evidence policy exists for the proposed cause");
        }
        Map<UUID, Evidence> byId = evidence.stream().collect(java.util.stream.Collectors.toMap(
                Evidence::evidenceId, value -> value, (first, second) -> second));
        Set<com.astrayzjt.faultpilot.common.domain.EvidenceType> supportingTypes = draft.supportingEvidenceIds()
                .stream().map(byId::get).filter(java.util.Objects::nonNull).map(Evidence::type)
                .collect(java.util.stream.Collectors.toSet());
        Set<com.astrayzjt.faultpilot.common.domain.EvidenceType> activeTypes = evidence.stream().map(Evidence::type)
                .collect(java.util.stream.Collectors.toSet());
        if (supportingTypes.stream().noneMatch(rule.signals()::contains)) {
            return MainAgentDecision.inconclusive("Diagnosis did not cite a required primary signal");
        }
        if (rule.counters().stream().anyMatch(activeTypes::contains)) {
            return MainAgentDecision.inconclusive("Active Evidence contradicts the proposed diagnosis");
        }
        boolean corroborated = rule.corroboration().stream().anyMatch(supportingTypes::contains);
        if (!corroborated) {
            LinkedHashSet<com.astrayzjt.faultpilot.common.domain.EvidenceType> missing =
                    new LinkedHashSet<>(draft.missingEvidenceTypes());
            missing.addAll(rule.corroboration());
            DiagnosisDraft supported = new DiagnosisDraft(DiagnosisStatus.SUPPORTED, draft.primaryCause(),
                    draft.contributingFactors(), draft.supportingEvidenceIds(), draft.counterEvidenceIds(),
                    List.copyOf(missing), draft.summary());
            return new MainAgentDecision(MainAgentAction.COMPLETE, List.of(), decision.evidenceIds(), supported,
                    decision.reason());
        }
        return decision;
    }

    private Map<CauseCode, EvidenceRule> evidenceRules() {
        EnumMap<CauseCode, EvidenceRule> rules = new EnumMap<>(CauseCode.class);
        rules.put(CauseCode.JVM_CPU_HOTSPOT, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.PROCESS_CPU_HIGH),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REPEATED_RUNNABLE_STACK,
                        com.astrayzjt.faultpilot.common.domain.EvidenceType.CPU_HOT_METHOD_FOUND),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.PROCESS_CPU_NORMAL)));
        rules.put(CauseCode.JVM_THREAD_POOL_EXHAUSTED, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.THREAD_POOL_ACTIVE_AT_MAX,
                        com.astrayzjt.faultpilot.common.domain.EvidenceType.THREAD_POOL_QUEUE_GROWING),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.BLOCKING_TASK_FOUND),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.THREAD_POOL_NORMAL)));
        rules.put(CauseCode.DB_SLOW_QUERY, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.SLOW_SQL_FOUND),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.API_AND_SQL_TIME_CORRELATED,
                        com.astrayzjt.faultpilot.common.domain.EvidenceType.ABNORMAL_EXECUTION_PLAN), Set.of()));
        rules.put(CauseCode.DB_POOL_EXHAUSTED, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.DB_POOL_PENDING_HIGH,
                        com.astrayzjt.faultpilot.common.domain.EvidenceType.DB_POOL_ACTIVE_AT_MAX),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.CONNECTION_HOLDING_QUERY_FOUND), Set.of()));
        rules.put(CauseCode.DEPENDENCY_TIMEOUT, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.DOWNSTREAM_LATENCY_HIGH),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.SLOW_CHILD_SPAN_FOUND), Set.of()));
        rules.put(CauseCode.REDIS_SERVER_LATENCY, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_COMMAND_LATENCY_HIGH),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_SLOW_COMMAND_FOUND,
                        com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_TRACE_LATENCY_CORRELATED),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_COMMAND_LATENCY_NORMAL)));
        rules.put(CauseCode.REDIS_CLIENT_POOL_EXHAUSTED, new EvidenceRule(
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_CLIENT_POOL_PENDING_HIGH),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_COMMAND_LATENCY_NORMAL),
                Set.of(com.astrayzjt.faultpilot.common.domain.EvidenceType.REDIS_CLIENT_POOL_NORMAL)));
        return Map.copyOf(rules);
    }

    private record EvidenceRule(
            Set<com.astrayzjt.faultpilot.common.domain.EvidenceType> signals,
            Set<com.astrayzjt.faultpilot.common.domain.EvidenceType> corroboration,
            Set<com.astrayzjt.faultpilot.common.domain.EvidenceType> counters) {
    }
}
