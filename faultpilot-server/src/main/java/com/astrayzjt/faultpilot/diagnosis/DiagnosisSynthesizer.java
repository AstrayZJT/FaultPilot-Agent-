package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.FollowUpRequest;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.common.domain.ProposalStatus;
import com.astrayzjt.faultpilot.common.domain.RoutingSignal;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.astrayzjt.faultpilot.common.model.ModelOutputInvalidException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@Service
public class DiagnosisSynthesizer {

    private final RemoteModelClient modelClient;
    private final ObjectMapper objectMapper;

    public DiagnosisSynthesizer(RemoteModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public DiagnosisProposal propose(com.astrayzjt.faultpilot.common.domain.IncidentSnapshot snapshot,
                                     List<Evidence> evidence, List<AgentFinding> findings,
                                     List<RoutingSignal> routingSignals, int round, int revision,
                                     com.astrayzjt.faultpilot.common.domain.DiagnosisCritique previousCritique) {
        String system = "You are FaultPilot Diagnosis Agent. Synthesize a diagnosis from only the supplied structured " +
                "Evidence and AgentFinding objects. Do not invent facts or evidence IDs. A proposal is not an action. " +
                "Return JSON only with fields: status, primaryCause, contributingFactors, supportingEvidenceIds, " +
                "counterEvidenceIds, missingEvidenceTypes, requestedFollowUps, causalSummary. " +
                "Use status READY_FOR_REVIEW when a cited direct signal supports a plausible causal explanation; " +
                "EvidenceGate, not you, decides whether missing corroboration lowers the final result to SUPPORTED. " +
                "Only structured Evidence with a valid evidenceId is auditable; do not repeat uncited observations " +
                "from AgentFinding prose as facts. REDIS_COMMAND_LATENCY_HIGH maps to REDIS_SERVER_LATENCY, the " +
                "catalog umbrella for end-to-end Redis command-path latency including network or connection delay. " +
                "REDIS_CLIENT_POOL_PENDING_HIGH maps to REDIS_CLIENT_POOL_EXHAUSTED. " +
                "Use INSUFFICIENT only when no cited direct signal supports a cause, and use CONTRADICTED when " +
                "cited counter evidence conflicts. requestedFollowUps entries must contain agentType, objective, " +
                "missingEvidenceTypes and evidenceIds. Empty arrays are valid and should be returned explicitly. " +
                "Allowed status values are READY_FOR_REVIEW, INSUFFICIENT, CONTRADICTED. Allowed primaryCause values are " +
                java.util.Arrays.toString(CauseCode.values()) + ".";
        String user = "round=" + round + "\nrevision=" + revision + "\nsnapshot=" + json(snapshot) +
                "\nroutingSignals=" + json(routingSignals) + "\nevidence=" + json(evidence) +
                "\nagentFindings=" + json(findings) + "\npreviousCritique=" + json(previousCritique);
        String raw = modelClient.complete(snapshot.incidentId(), null, ModelRole.DIAGNOSIS_SYNTHESIZER,
                "synthesis-v2", system, user, 1000);
        try {
            return parse(raw, snapshot.incidentId(), round, revision, evidence);
        } catch (RuntimeException exception) {
            Set<UUID> allowedEvidenceIds = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            String repaired = modelClient.complete(snapshot.incidentId(), null, ModelRole.DIAGNOSIS_SYNTHESIZER,
                    "synthesis-repair-v2", "Repair the draft into one JSON object only. Use the exact schema and allowed enum " +
                            "values from the original request. Missing optional arrays must be []. Map a final " +
                            "CONFIRMED/SUPPORTED diagnosis to READY_FOR_REVIEW because EvidenceGate performs the final " +
                            "decision. Never invent an evidence ID; only use IDs from AllowedEvidenceIds.",
                    "Draft=" + raw + "\nAllowedEvidenceIds=" + json(allowedEvidenceIds), 1000);
            try {
                return parse(repaired, snapshot.incidentId(), round, revision, evidence);
            } catch (RuntimeException ignored) {
                throw new ModelOutputInvalidException(ModelRole.DIAGNOSIS_SYNTHESIZER);
            }
        }
    }

    private DiagnosisProposal parse(String raw, UUID incidentId, int round, int revision, List<Evidence> evidence) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            Set<UUID> allowed = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            CauseCode cause = causeCode(text(node, "primaryCause", "causeCode", "rootCause", "cause"));
            ProposalStatus status = proposalStatus(text(node, "status", "diagnosisStatus", "result"));
            if (cause == CauseCode.UNKNOWN && status == ProposalStatus.READY_FOR_REVIEW) {
                status = ProposalStatus.INSUFFICIENT;
            }
            List<UUID> supporting = allowedIds(node, allowed, "supportingEvidenceIds", "supportingEvidence", "evidenceIds");
            List<UUID> counter = allowedIds(node, allowed, "counterEvidenceIds", "counterEvidence", "refutingEvidenceIds");
            List<FollowUpRequest> followUps = parseFollowUps(node, allowed);
            List<CauseCode> contributingFactors = enumValues(node, this::causeCode,
                    "contributingFactors", "contributingCauses").stream()
                    .filter(candidate -> candidate != CauseCode.UNKNOWN && candidate != cause).toList();
            return new DiagnosisProposal(UUID.randomUUID(), incidentId, round, revision, status, cause,
                    contributingFactors, supporting, counter,
                    enumValues(node, this::evidenceType, "missingEvidenceTypes", "missingEvidence", "missingChecks"), followUps,
                    text(node, "causalSummary", "summary", "explanation", "reasoning"));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Diagnosis Agent output is not a valid proposal", exception);
        }
    }

    private List<FollowUpRequest> parseFollowUps(JsonNode node, Set<UUID> allowed) {
        JsonNode nodes = field(node, "requestedFollowUps", "followUps", "followUpRequests", "nextChecks");
        if (nodes == null || !nodes.isArray()) {
            return List.of();
        }
        List<FollowUpRequest> result = new ArrayList<>();
        nodes.forEach(followUp -> {
            AgentType agent = agentType(text(followUp, "agentType", "suggestedAgent", "nextAgent"));
            String objective = text(followUp, "objective", "reason", "check", "question", "summary");
            if (agent != null && !objective.isBlank()) {
                result.add(new FollowUpRequest(agent, objective,
                        enumValues(followUp, this::evidenceType, "missingEvidenceTypes", "missingChecks", "requiredEvidenceTypes"),
                        allowedIds(followUp, allowed, "evidenceIds", "supportingEvidenceIds")));
            }
        });
        return List.copyOf(result);
    }

    private List<UUID> allowedIds(JsonNode node, Set<UUID> allowed, String... names) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (String name : names) {
            JsonNode values = field(node, name);
            if (values == null || values.isNull()) {
                continue;
            }
            if (values.isArray()) {
                values.forEach(value -> addAllowedId(value, allowed, result));
            } else {
                addAllowedId(values, allowed, result);
            }
        }
        return List.copyOf(result);
    }

    private void addAllowedId(JsonNode value, Set<UUID> allowed, Set<UUID> result) {
        String raw = value != null && value.isObject() ? text(value, "evidenceId", "id") : value == null ? "" : value.asText("");
        try {
            UUID id = UUID.fromString(raw.trim());
            if (allowed.contains(id)) {
                result.add(id);
            }
        } catch (IllegalArgumentException ignored) {
            // Unknown or malformed model references are deliberately dropped before EvidenceGate.
        }
    }

    private <T extends Enum<T>> List<T> enumValues(JsonNode node, Function<String, T> parser, String... names) {
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (String name : names) {
            JsonNode values = field(node, name);
            if (values == null || values.isNull()) {
                continue;
            }
            if (!values.isArray()) {
                T parsed = parser.apply(values.asText(""));
                if (parsed != null) {
                    result.add(parsed);
                }
                continue;
            }
            values.forEach(value -> {
                String raw = value != null && value.isObject() ? text(value, "type", "name", "code") : value.asText("");
                T parsed = parser.apply(raw);
                if (parsed != null) {
                    result.add(parsed);
                }
            });
        }
        return List.copyOf(result);
    }

    private ProposalStatus proposalStatus(String raw) {
        return switch (normalizeEnum(raw)) {
            case "READY_FOR_REVIEW", "READY", "READY_FOR_DIAGNOSIS", "SUPPORTED", "CONFIRMED", "DIAGNOSED", "SUCCESS" -> ProposalStatus.READY_FOR_REVIEW;
            case "CONTRADICTED", "CONTRADICTORY", "REJECTED", "REFUTED", "FAILED" -> ProposalStatus.CONTRADICTED;
            default -> ProposalStatus.INSUFFICIENT;
        };
    }

    private CauseCode causeCode(String raw) {
        String value = normalizeEnum(raw);
        try {
            return CauseCode.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return switch (value) {
                case "CPU_HOTSPOT", "JVM_CPU_HIGH", "JVM_HIGH_CPU", "HIGH_CPU", "PROCESS_CPU_HIGH" -> CauseCode.JVM_CPU_HOTSPOT;
                case "THREAD_POOL_EXHAUSTED", "THREAD_POOL_SATURATION", "JVM_THREAD_POOL_SATURATION",
                        "THREAD_POOL_ACTIVE_AT_MAX", "THREAD_POOL_QUEUE_GROWING" -> CauseCode.JVM_THREAD_POOL_EXHAUSTED;
                case "SLOW_SQL", "SLOW_SQL_FOUND", "SQL_SLOW", "SLOW_QUERY", "DATABASE_SLOW_QUERY" -> CauseCode.DB_SLOW_QUERY;
                case "DB_CONNECTION_POOL_EXHAUSTED", "DATABASE_CONNECTION_POOL_EXHAUSTED", "DATABASE_POOL_EXHAUSTED",
                        "CONNECTION_POOL_EXHAUSTED", "DB_POOL_SATURATION", "DB_POOL_ACTIVE_AT_MAX",
                        "DB_POOL_PENDING_HIGH" -> CauseCode.DB_POOL_EXHAUSTED;
                case "DOWNSTREAM_TIMEOUT", "DOWNSTREAM_LATENCY_HIGH", "DOWNSTREAM_SLOW", "SERVICE_TIMEOUT",
                        "DEPENDENCY_LATENCY_HIGH", "DEPENDENCY_SLOW" -> CauseCode.DEPENDENCY_TIMEOUT;
                case "REDIS_LATENCY", "REDIS_LATENCY_HIGH", "REDIS_COMMAND_LATENCY_HIGH", "REDIS_SERVER_SLOW",
                        "REDIS_SERVER_LATENCY_HIGH", "CACHE_SERVER_LATENCY", "CACHE_LATENCY_HIGH",
                        "CACHE_COMMAND_LATENCY_HIGH", "HIGH_REDIS_LATENCY" -> CauseCode.REDIS_SERVER_LATENCY;
                case "CACHE_CLIENT_POOL_EXHAUSTED", "CACHE_CLIENT_POOL_SATURATED", "REDIS_POOL_EXHAUSTED",
                        "CACHE_POOL_EXHAUSTED", "REDIS_CLIENT_POOL_SATURATED", "REDIS_CLIENT_POOL_PENDING_HIGH",
                        "REDIS_CLIENT_CONNECTION_POOL_EXHAUSTED" -> CauseCode.REDIS_CLIENT_POOL_EXHAUSTED;
                default -> CauseCode.UNKNOWN;
            };
        }
    }

    private EvidenceType evidenceType(String raw) {
        String value = normalizeEnum(raw);
        try {
            return EvidenceType.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return switch (value) {
                case "CPU_HIGH", "JVM_CPU_HIGH", "PROCESS_CPU_HOT" -> EvidenceType.PROCESS_CPU_HIGH;
                case "CPU_NORMAL", "JVM_CPU_NORMAL" -> EvidenceType.PROCESS_CPU_NORMAL;
                case "CPU_HOT_METHOD" -> EvidenceType.CPU_HOT_METHOD_FOUND;
                case "THREAD_POOL_SATURATED", "THREAD_POOL_EXHAUSTED", "JVM_THREAD_POOL_EXHAUSTED" -> EvidenceType.THREAD_POOL_ACTIVE_AT_MAX;
                case "THREAD_QUEUE_GROWING", "EXECUTOR_QUEUE_GROWING" -> EvidenceType.THREAD_POOL_QUEUE_GROWING;
                case "SLOW_QUERY", "SLOW_SQL" -> EvidenceType.SLOW_SQL_FOUND;
                case "DB_POOL_HIGH", "CONNECTION_POOL_EXHAUSTED", "DATABASE_POOL_EXHAUSTED" -> EvidenceType.DB_POOL_ACTIVE_AT_MAX;
                case "DOWNSTREAM_TIMEOUT", "DEPENDENCY_LATENCY_HIGH" -> EvidenceType.DOWNSTREAM_LATENCY_HIGH;
                case "REDIS_LATENCY_HIGH", "CACHE_SERVER_LATENCY" -> EvidenceType.REDIS_COMMAND_LATENCY_HIGH;
                case "REDIS_LATENCY_NORMAL", "CACHE_SERVER_LATENCY_NORMAL" -> EvidenceType.REDIS_COMMAND_LATENCY_NORMAL;
                default -> null;
            };
        }
    }

    private AgentType agentType(String raw) {
        String value = normalizeEnum(raw);
        return switch (value) {
            case "JVM", "JVM_AGENT" -> AgentType.JVM_AGENT;
            case "DATABASE", "DB", "DATABASE_AGENT" -> AgentType.DATABASE_AGENT;
            case "DEPENDENCY", "DOWNSTREAM", "DEPENDENCY_AGENT" -> AgentType.DEPENDENCY_AGENT;
            case "CACHE", "REDIS", "CACHE_AGENT" -> AgentType.CACHE_AGENT;
            default -> null;
        };
    }

    private String normalizeEnum(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private JsonNode field(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null) {
                return value;
            }
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String actual = fields.next();
            for (String name : names) {
                if (actual.equalsIgnoreCase(name)) {
                    return node.get(actual);
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String extractJson(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in Diagnosis Agent output");
        }
        return raw.substring(start, end + 1);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
