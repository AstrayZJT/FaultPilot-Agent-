package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CriticVerdict;
import com.astrayzjt.faultpilot.common.domain.CritiqueIssue;
import com.astrayzjt.faultpilot.common.domain.CritiqueIssueType;
import com.astrayzjt.faultpilot.common.domain.DiagnosisCritique;
import com.astrayzjt.faultpilot.common.domain.DiagnosisProposal;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
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
public class DiagnosisCritic {

    private final RemoteModelClient modelClient;
    private final ObjectMapper objectMapper;

    public DiagnosisCritic(RemoteModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public DiagnosisCritique review(com.astrayzjt.faultpilot.common.domain.IncidentSnapshot snapshot,
                                    DiagnosisProposal proposal, List<Evidence> evidence,
                                    List<AgentFinding> findings) {
        String system = "You are FaultPilot Critic Agent. Independently audit the supplied DiagnosisProposal against " +
                "the raw structured Evidence and AgentFindings. Do not invent facts. Check unsupported causal claims, " +
                "counter evidence, alternative causes, missing high-value checks and unsafe remediation claims. " +
                "Return JSON only: {verdict,issues:[{type,summary,evidenceIds,missingEvidenceTypes,suggestedAgent}],summary}. " +
                "Use PASS only when the proposal is adequately supported. Empty issues and arrays are valid. " +
                "Allowed verdicts are PASS, REVISE, FOLLOW_UP, REJECT.";
        String user = "snapshot=" + json(snapshot) + "\nproposal=" + json(proposal) +
                "\nevidence=" + json(evidence) + "\nagentFindings=" + json(findings);
        String raw = modelClient.complete(snapshot.incidentId(), null, ModelRole.CRITIC,
                "review-v2", system, user, 900);
        try {
            return parse(raw, proposal.proposalId(), evidence);
        } catch (RuntimeException exception) {
            Set<UUID> allowedEvidenceIds = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            String repaired = modelClient.complete(snapshot.incidentId(), null, ModelRole.CRITIC,
                    "review-repair-v2", "Repair the draft into one JSON object only. Use the exact critique schema and " +
                            "allowed enum values. Missing optional arrays must be []. Never invent evidence IDs; only use " +
                            "IDs from AllowedEvidenceIds.",
                    "Draft=" + raw + "\nAllowedEvidenceIds=" + json(allowedEvidenceIds), 900);
            try {
                return parse(repaired, proposal.proposalId(), evidence);
            } catch (RuntimeException ignored) {
                throw new ModelOutputInvalidException(ModelRole.CRITIC);
            }
        }
    }

    private DiagnosisCritique parse(String raw, UUID proposalId, List<Evidence> evidence) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            Set<UUID> allowed = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            CriticVerdict verdict = verdict(text(node, "verdict", "status", "result"));
            JsonNode issueNodes = field(node, "issues", "critiqueIssues", "findings");
            List<CritiqueIssue> issues = new ArrayList<>();
            if (issueNodes != null && issueNodes.isArray()) {
                issueNodes.forEach(issue -> {
                    if (issue != null && issue.isObject()) {
                        issues.add(new CritiqueIssue(
                                issueType(text(issue, "type", "issueType", "category")),
                                text(issue, "summary", "description", "reason"),
                                allowedIds(issue, allowed, "evidenceIds", "supportingEvidenceIds", "counterEvidenceIds"),
                                enumValues(issue, this::evidenceType, "missingEvidenceTypes", "missingChecks", "requiredEvidenceTypes"),
                                optionalAgent(issue, "suggestedAgent", "nextAgent", "agentType")));
                    }
                });
            }
            return new DiagnosisCritique(UUID.randomUUID(), proposalId, verdict, issues,
                    text(node, "summary", "overallSummary", "explanation", "reasoning"));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Critic output is not a valid critique", exception);
        }
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
            // Unknown or malformed model references are deliberately dropped before the gate.
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

    private CriticVerdict verdict(String raw) {
        return switch (normalizeEnum(raw)) {
            case "PASS", "PASSED", "APPROVE", "APPROVED", "ACCEPT", "ACCEPTED", "CONFIRMED" -> CriticVerdict.PASS;
            case "REVISE", "REVISION", "NEEDS_REVISION", "REVIEW" -> CriticVerdict.REVISE;
            case "FOLLOW_UP", "FOLLOWUP", "NEEDS_MORE_EVIDENCE", "INSUFFICIENT" -> CriticVerdict.FOLLOW_UP;
            case "REJECT", "REJECTED", "FAIL", "FAILED", "REFUTED", "CONTRADICTED" -> CriticVerdict.REJECT;
            default -> CriticVerdict.REJECT;
        };
    }

    private CritiqueIssueType issueType(String raw) {
        return switch (normalizeEnum(raw)) {
            case "UNSUPPORTED_CLAIM", "UNSUPPORTED_CAUSE", "UNSUPPORTED_CONCLUSION" -> CritiqueIssueType.UNSUPPORTED_CLAIM;
            case "UNRESOLVED_COUNTER_EVIDENCE", "COUNTER_EVIDENCE", "CONTRADICTING_EVIDENCE" -> CritiqueIssueType.UNRESOLVED_COUNTER_EVIDENCE;
            case "ALTERNATIVE_CAUSE", "ALTERNATIVE_ROOT_CAUSE" -> CritiqueIssueType.ALTERNATIVE_CAUSE;
            case "MISSING_HIGH_VALUE_CHECK", "MISSING_EVIDENCE", "MISSING_CHECK", "INSUFFICIENT_EVIDENCE" -> CritiqueIssueType.MISSING_HIGH_VALUE_CHECK;
            case "INVALID_EVIDENCE_REFERENCE", "INVALID_EVIDENCE", "HALLUCINATED_EVIDENCE" -> CritiqueIssueType.INVALID_EVIDENCE_REFERENCE;
            case "UNSAFE_REMEDIATION_CLAIM", "UNSAFE_REMEDIATION" -> CritiqueIssueType.UNSAFE_REMEDIATION_CLAIM;
            default -> CritiqueIssueType.MISSING_HIGH_VALUE_CHECK;
        };
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
                case "SLOW_QUERY", "SLOW_SQL" -> EvidenceType.SLOW_SQL_FOUND;
                case "DB_POOL_HIGH", "CONNECTION_POOL_EXHAUSTED", "DATABASE_POOL_EXHAUSTED" -> EvidenceType.DB_POOL_ACTIVE_AT_MAX;
                case "DOWNSTREAM_TIMEOUT", "DEPENDENCY_LATENCY_HIGH" -> EvidenceType.DOWNSTREAM_LATENCY_HIGH;
                case "REDIS_LATENCY_HIGH", "CACHE_SERVER_LATENCY" -> EvidenceType.REDIS_COMMAND_LATENCY_HIGH;
                default -> null;
            };
        }
    }

    private AgentType optionalAgent(JsonNode node, String... names) {
        String value = text(node, names);
        return switch (normalizeEnum(value)) {
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
            throw new IllegalArgumentException("No JSON object in Critic output");
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
