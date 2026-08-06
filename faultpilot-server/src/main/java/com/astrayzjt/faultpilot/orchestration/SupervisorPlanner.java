package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.time.Instant;

@Component
public class SupervisorPlanner {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final TraceRepository traceRepository;

    public SupervisorPlanner(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper,
                             TraceRepository traceRepository) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
        this.traceRepository = traceRepository;
    }

    public InvestigationPlan plan(IncidentSnapshot snapshot, List<Evidence> evidence, int round) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            return deterministicPlan(snapshot, round);
        }
        String system = "You are FaultPilot Supervisor. Select only JVM_AGENT, DATABASE_AGENT, or " +
                "DEPENDENCY_AGENT. Return JSON only: {\"tasks\":[{\"agentType\":\"...\",\"objective\":\"...\",\"evidenceIds\":[]}],\"reason\":\"...\"}. " +
                "Choose the smallest useful set, at most 3 tasks. Never invent an evidence ID.";
        String user = "round=" + round + "\nsnapshot=" + json(snapshot) + "\nevidence=" + json(evidence);
        Instant startedAt = Instant.now();
        String raw;
        try {
            raw = model.chat(ChatRequest.builder()
                            .messages(List.of(SystemMessage.from(system), UserMessage.from(user)))
                            .temperature(0.0).maxOutputTokens(500).build())
                    .aiMessage().text();
            traceRepository.model(snapshot.incidentId(), null, model.getClass().getSimpleName(),
                    "supervisor-plan-v1", null, null, startedAt, "SUCCEEDED");
        } catch (RuntimeException exception) {
            traceRepository.model(snapshot.incidentId(), null, model.getClass().getSimpleName(),
                    "supervisor-plan-v1", null, null, startedAt, "FAILED");
            throw exception;
        }
        try {
            return parse(raw);
        } catch (RuntimeException exception) {
            return deterministicPlan(snapshot, round);
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

    private InvestigationPlan deterministicPlan(IncidentSnapshot snapshot, int round) {
        String text = (snapshot.symptom() == null ? "" : snapshot.symptom()).toLowerCase(Locale.ROOT);
        List<AgentType> types;
        if (round > 1) {
            types = snapshot.serviceName().equals("inventory-service")
                    ? List.of(AgentType.DEPENDENCY_AGENT)
                    : List.of(AgentType.JVM_AGENT, AgentType.DATABASE_AGENT);
        } else if (text.contains("sql") || text.contains("database") || text.contains("数据库")) {
            types = List.of(AgentType.JVM_AGENT, AgentType.DATABASE_AGENT);
        } else if (text.contains("cpu") || text.contains("线程") || text.contains("thread")) {
            types = List.of(AgentType.JVM_AGENT);
        } else if (text.contains("下游") || text.contains("timeout") || text.contains("库存") || text.contains("dependency")) {
            types = List.of(AgentType.DEPENDENCY_AGENT);
        } else {
            types = snapshot.serviceName().equals("inventory-service")
                    ? List.of(AgentType.DEPENDENCY_AGENT)
                    : List.of(AgentType.JVM_AGENT, AgentType.DATABASE_AGENT, AgentType.DEPENDENCY_AGENT);
        }
        return new InvestigationPlan(types.stream()
                .map(type -> new AgentTaskDraft(type, "Investigate " + snapshot.serviceName() + " for " + type, List.of()))
                .toList(), "Deterministic safe plan used because structured model planning was unavailable");
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
