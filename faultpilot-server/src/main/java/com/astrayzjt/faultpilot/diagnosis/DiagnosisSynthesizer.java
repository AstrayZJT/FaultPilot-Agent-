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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
                "Use status READY_FOR_REVIEW only when a plausible causal explanation exists; use INSUFFICIENT or " +
                "CONTRADICTED when appropriate. requestedFollowUps entries must contain agentType, objective, " +
                "missingEvidenceTypes and evidenceIds.";
        String user = "round=" + round + "\nrevision=" + revision + "\nsnapshot=" + json(snapshot) +
                "\nroutingSignals=" + json(routingSignals) + "\nevidence=" + json(evidence) +
                "\nagentFindings=" + json(findings) + "\npreviousCritique=" + json(previousCritique);
        String raw = modelClient.complete(snapshot.incidentId(), null, ModelRole.DIAGNOSIS_SYNTHESIZER,
                "synthesis-v2", system, user, 1000);
        try {
            return parse(raw, snapshot.incidentId(), round, revision, evidence);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(snapshot.incidentId(), null, ModelRole.DIAGNOSIS_SYNTHESIZER,
                    "synthesis-repair-v2", "Return only valid JSON matching the requested diagnosis proposal schema. Do not add commentary.", raw, 1000);
            return parse(repaired, snapshot.incidentId(), round, revision, evidence);
        }
    }

    private DiagnosisProposal parse(String raw, UUID incidentId, int round, int revision, List<Evidence> evidence) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            Set<UUID> allowed = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            ProposalStatus status = ProposalStatus.valueOf(requiredText(node, "status").toUpperCase());
            CauseCode cause = CauseCode.valueOf(requiredText(node, "primaryCause").toUpperCase());
            List<UUID> supporting = strictIds(node, "supportingEvidenceIds", allowed);
            List<UUID> counter = strictIds(node, "counterEvidenceIds", allowed);
            List<FollowUpRequest> followUps = parseFollowUps(node.path("requestedFollowUps"), allowed);
            return new DiagnosisProposal(UUID.randomUUID(), incidentId, round, revision, status, cause,
                    enums(node, "contributingFactors", CauseCode.class), supporting, counter,
                    enums(node, "missingEvidenceTypes", EvidenceType.class), followUps,
                    node.path("causalSummary").asText(""));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Diagnosis Agent output is not a valid proposal", exception);
        }
    }

    private List<FollowUpRequest> parseFollowUps(JsonNode nodes, Set<UUID> allowed) {
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("requestedFollowUps must be an array");
        }
        List<FollowUpRequest> result = new ArrayList<>();
        nodes.forEach(node -> {
            AgentType agent = AgentType.valueOf(requiredText(node, "agentType").toUpperCase());
            String objective = requiredText(node, "objective");
            List<UUID> ids = strictIds(node, "evidenceIds", allowed);
            result.add(new FollowUpRequest(agent, objective, enums(node, "missingEvidenceTypes", EvidenceType.class), ids));
        });
        return List.copyOf(result);
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
                    throw new IllegalArgumentException("Evidence ID is outside this Incident: " + id);
                }
                result.add(id);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid Evidence ID in " + name, exception);
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

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing proposal field: " + field);
        }
        return value;
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
