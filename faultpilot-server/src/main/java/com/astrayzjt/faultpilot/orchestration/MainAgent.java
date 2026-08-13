package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentCapability;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.common.model.ModelOutputInvalidException;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public final class MainAgent {

    private final RemoteModelClient modelClient;
    private final ObjectMapper objectMapper;

    public MainAgent(RemoteModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public MainAgentDecision decide(MainAgentContext context) {
        String system = "You are FaultPilot Main Agent. Reflect on the Incident, current active Evidence, prior " +
                "delegations and available specialist Agent capability summaries. Choose exactly one action: " +
                "DELEGATE, COMPLETE, or INCONCLUSIVE. Treat the user symptom as a weak prior and prefer structured " +
                "Evidence. DELEGATE may select one to three supplied specialist Agents for independent parallel " +
                "investigations. Each delegation must provide a diagnostic objective, " +
                "not a URL, credential, SQL statement, tool name, or shell/Arthas/Redis command. COMPLETE requires " +
                "a supported DiagnosisDraft citing only supplied Evidence IDs. Do not invent facts or IDs. Return " +
                "JSON only: {action,delegations:[{agentType,objective}],evidenceIds,diagnosis,reason}. diagnosis fields are " +
                "{status,primaryCause,contributingFactors,supportingEvidenceIds,counterEvidenceIds," +
                "missingEvidenceTypes,summary}.";
        String user = "runId=" + context.run().runId() +
                "\nround=" + context.nextRound() +
                "\nremainingRounds=" + context.remainingRounds() +
                "\nremainingDelegations=" + context.remainingDelegations() +
                "\ndeadline=" + context.deadline() +
                "\nincident=" + json(context.incident()) +
                "\navailableAgents=" + json(context.capabilities().agents().stream()
                .map(CapabilityView::from).toList()) +
                "\nevidence=" + json(context.evidence()) +
                "\ndelegationHistory=" + json(context.delegations().stream()
                .map(DelegationView::from).toList());
        String raw = modelClient.complete(context.incident().incidentId(), null, ModelRole.MAIN_AGENT,
                "main-loop-v1", system, user, 1_000);
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(context.incident().incidentId(), null, ModelRole.MAIN_AGENT,
                    "main-loop-repair-v1",
                    "Return exactly one valid JSON object for the FaultPilot MainAgentDecision schema. " +
                            "Use only DELEGATE, COMPLETE, or INCONCLUSIVE and do not add commentary.", raw, 1_000);
            try {
                return parse(repaired);
            } catch (RuntimeException ignored) {
                throw new ModelOutputInvalidException(ModelRole.MAIN_AGENT);
            }
        }
    }

    MainAgentDecision parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            MainAgentAction action = MainAgentAction.valueOf(requiredText(root, "action").toUpperCase(Locale.ROOT));
            List<SpecialistDelegation> delegations = parseDelegations(root);
            DiagnosisDraft draft = root.hasNonNull("diagnosis") ? parseDraft(root.path("diagnosis")) : null;
            return new MainAgentDecision(action, delegations, uuidList(root.path("evidenceIds")), draft,
                    root.path("reason").asText(""));
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("Main Agent output is not a valid decision", exception);
        }
    }

    private List<SpecialistDelegation> parseDelegations(JsonNode root) {
        JsonNode nodes = root.path("delegations");
        if (nodes.isMissingNode() || nodes.isNull()) {
            if (root.hasNonNull("agentType") && root.hasNonNull("objective")) {
                return List.of(new SpecialistDelegation(
                        optionalEnum(root.path("agentType"), AgentType.class),
                        root.path("objective").asText("")));
            }
            return List.of();
        }
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("delegations must be an array");
        }
        List<SpecialistDelegation> result = new ArrayList<>();
        nodes.forEach(node -> result.add(new SpecialistDelegation(
                optionalEnum(node.path("agentType"), AgentType.class), node.path("objective").asText(""))));
        return List.copyOf(result);
    }

    private DiagnosisDraft parseDraft(JsonNode node) {
        return new DiagnosisDraft(
                optionalEnum(node.path("status"), DiagnosisStatus.class),
                optionalEnum(node.path("primaryCause"), CauseCode.class),
                enumList(node.path("contributingFactors"), CauseCode.class),
                uuidList(node.path("supportingEvidenceIds")),
                uuidList(node.path("counterEvidenceIds")),
                enumList(node.path("missingEvidenceTypes"), EvidenceType.class),
                node.path("summary").asText(""));
    }

    private List<UUID> uuidList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("Evidence IDs must be an array");
        }
        List<UUID> values = new ArrayList<>();
        node.forEach(value -> values.add(UUID.fromString(value.asText())));
        return List.copyOf(values);
    }

    private <T extends Enum<T>> List<T> enumList(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(type.getSimpleName() + " values must be an array");
        }
        List<T> values = new ArrayList<>();
        node.forEach(value -> values.add(Enum.valueOf(type, value.asText().toUpperCase(Locale.ROOT))));
        return List.copyOf(values);
    }

    private <T extends Enum<T>> T optionalEnum(JsonNode node, Class<T> type) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            return null;
        }
        return Enum.valueOf(type, node.asText().trim().toUpperCase(Locale.ROOT));
    }

    private String requiredText(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String extractJson(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in Main Agent output");
        }
        return raw.substring(start, end + 1);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Main Agent context", exception);
        }
    }

    private record CapabilityView(String agentId, AgentType agentType, String name, String description,
                                  String capabilityVersion, String status) {
        private static CapabilityView from(AgentCapability capability) {
            return new CapabilityView(capability.agentId(), capability.agentType(), capability.name(),
                    capability.description(), capability.capabilityVersion(), capability.status().name());
        }
    }

    private record DelegationView(int round, String agentId, AgentType agentType, String objective, String status,
                                  List<UUID> evidenceIds, String errorCode) {
        private static DelegationView from(AgentDelegation delegation) {
            List<UUID> evidenceIds = delegation.artifact() == null ? List.of() : delegation.artifact().evidenceIds();
            return new DelegationView(delegation.round(), delegation.agentId(), delegation.agentType(),
                    delegation.objective(), delegation.status().name(), evidenceIds, delegation.errorCode());
        }
    }
}
