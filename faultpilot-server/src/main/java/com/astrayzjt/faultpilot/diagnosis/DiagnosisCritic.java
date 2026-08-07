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
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
                "Use PASS only when the proposal is adequately supported.";
        String user = "snapshot=" + json(snapshot) + "\nproposal=" + json(proposal) +
                "\nevidence=" + json(evidence) + "\nagentFindings=" + json(findings);
        String raw = modelClient.complete(snapshot.incidentId(), null, ModelRole.CRITIC,
                "review-v2", system, user, 900);
        try {
            return parse(raw, proposal.proposalId(), evidence);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(snapshot.incidentId(), null, ModelRole.CRITIC,
                    "review-repair-v2", "Return only valid JSON matching the requested critique schema. Do not add commentary.", raw, 900);
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
            CriticVerdict verdict = CriticVerdict.valueOf(requiredText(node, "verdict").toUpperCase());
            JsonNode issueNodes = node.path("issues");
            if (!issueNodes.isArray()) {
                throw new IllegalArgumentException("issues must be an array");
            }
            List<CritiqueIssue> issues = new ArrayList<>();
            issueNodes.forEach(issue -> issues.add(new CritiqueIssue(
                    CritiqueIssueType.valueOf(requiredText(issue, "type").toUpperCase()),
                    requiredText(issue, "summary"), strictIds(issue, "evidenceIds", allowed),
                    enums(issue, "missingEvidenceTypes", EvidenceType.class), optionalAgent(issue, "suggestedAgent"))));
            return new DiagnosisCritique(UUID.randomUUID(), proposalId, verdict, issues,
                    node.path("summary").asText(""));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Critic output is not a valid critique", exception);
        }
    }

    private List<UUID> strictIds(JsonNode node, String name, Set<UUID> allowed) {
        JsonNode values = node.path(name);
        if (!values.isArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<UUID> result = new ArrayList<>();
        values.forEach(value -> {
            try {
                UUID id = UUID.fromString(value.asText());
                if (!allowed.contains(id)) {
                    throw new IllegalArgumentException("Evidence ID is outside this Incident");
                }
                result.add(id);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid Evidence ID in critique", exception);
            }
        });
        return List.copyOf(result);
    }

    private <T extends Enum<T>> List<T> enums(JsonNode node, String name, Class<T> type) {
        JsonNode values = node.path(name);
        if (!values.isArray()) {
            throw new IllegalArgumentException(name + " must be an array");
        }
        List<T> result = new ArrayList<>();
        values.forEach(value -> result.add(Enum.valueOf(type, value.asText().toUpperCase())));
        return List.copyOf(result);
    }

    private AgentType optionalAgent(JsonNode node, String name) {
        String value = node.path(name).asText("");
        return value.isBlank() || "NULL".equalsIgnoreCase(value) ? null : AgentType.valueOf(value.toUpperCase());
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing critique field: " + field);
        }
        return value;
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
