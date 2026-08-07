package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.RoutingSignal;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
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
public class SupervisorPlanner {

    private final ObjectMapper objectMapper;
    private final RemoteModelClient modelClient;

    public SupervisorPlanner(ObjectMapper objectMapper, RemoteModelClient modelClient) {
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
    }

    public InvestigationPlan plan(IncidentSnapshot snapshot, List<Evidence> evidence, int round) {
        return plan(snapshot, evidence, List.of(), round);
    }

    public InvestigationPlan plan(IncidentSnapshot snapshot, List<Evidence> evidence, List<RoutingSignal> routingSignals, int round) {
        String system = "You are FaultPilot Supervisor. Select only JVM_AGENT, DATABASE_AGENT, DEPENDENCY_AGENT, or " +
                "CACHE_AGENT. Return JSON only: {\"tasks\":[{\"agentType\":\"...\",\"objective\":\"...\",\"evidenceIds\":[]}],\"reason\":\"...\"}. " +
                "Choose the smallest useful set, at most 3 tasks. Never invent an evidence ID.";
        String user = "round=" + round + "\nsnapshot=" + json(snapshot) + "\nroutingSignals=" + json(routingSignals) + "\nevidence=" + json(evidence);
        String raw = modelClient.complete(snapshot.incidentId(), null, ModelRole.SUPERVISOR,
                "plan-v2", system, user, 600);
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(snapshot.incidentId(), null, ModelRole.SUPERVISOR,
                    "plan-repair-v2", "Return only a valid JSON object matching the requested InvestigationPlan schema. Do not add commentary.", raw, 600);
            return parse(repaired);
        }
    }

    InvestigationPlan parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(extractJson(raw));
            List<AgentTaskDraft> tasks = new ArrayList<>();
            root.path("tasks").forEach(node -> {
                try {
                    AgentType type = AgentType.valueOf(node.path("agentType").asText().toUpperCase(Locale.ROOT));
                    List<UUID> evidenceIds = new ArrayList<>();
                    node.path("evidenceIds").forEach(id -> {
                        try {
                            evidenceIds.add(UUID.fromString(id.asText()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                    tasks.add(new AgentTaskDraft(type, node.path("objective").asText(), evidenceIds));
                } catch (IllegalArgumentException ignored) {
                }
            });
            return new InvestigationPlan(tasks, root.path("reason").asText("Model-generated investigation plan"));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Supervisor output is not valid JSON", exception);
        }
    }

    private String extractJson(String raw) {
        int start = raw == null ? -1 : raw.indexOf('{');
        int end = raw == null ? -1 : raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in Supervisor output");
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
