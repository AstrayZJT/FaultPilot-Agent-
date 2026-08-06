package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class SpecialistAgentRunner {

    private final ToolRegistry toolRegistry;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final TraceRepository traceRepository;

    public SpecialistAgentRunner(ToolRegistry toolRegistry, EvidenceService evidenceService,
                                 ObjectMapper objectMapper, ObjectProvider<ChatModel> chatModelProvider,
                                 TraceRepository traceRepository) {
        this.toolRegistry = toolRegistry;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
        this.chatModelProvider = chatModelProvider;
        this.traceRepository = traceRepository;
    }

    public AgentFinding run(AgentTask task, IncidentSnapshot snapshot, List<Evidence> existingEvidence) {
        Instant deadline = Instant.now().plusSeconds(20);
        List<Evidence> collected = new ArrayList<>(existingEvidence);
        List<ToolResult> results = new ArrayList<>();
        Set<String> calledTools = new HashSet<>();
        for (String toolName : toolRegistry.names(task.agentType())) {
            if (results.size() >= task.maxSteps() || !calledTools.add(toolName)) {
                break;
            }
            ToolExecutionContext context = new ToolExecutionContext(task.incidentId(), task.taskId(),
                    task.agentType(), snapshot.serviceName(), deadline);
            DiagnosticTool<?> tool = toolRegistry.require(toolName, task.agentType());
            Instant toolStartedAt = Instant.now();
            ToolResult result;
            try {
                result = execute(tool, context);
                traceRepository.tool(task.incidentId(), task.taskId(), task.agentType().name(), tool.name(),
                        "empty", result.success() ? "SUCCEEDED" : "FAILED", result.summary(), null,
                        toolStartedAt, Instant.now());
            } catch (RuntimeException exception) {
                traceRepository.tool(task.incidentId(), task.taskId(), task.agentType().name(), tool.name(),
                        "empty", "FAILED", null, exception.getClass().getSimpleName(), toolStartedAt, Instant.now());
                throw exception;
            }
            results.add(result);
            Evidence evidence = evidenceService.record(task.incidentId(), task.taskId(), result,
                    snapshot.timeRange().start(), snapshot.timeRange().end());
            if (evidence != null) {
                collected.add(evidence);
            }
        }
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("Qwen ChatModel is not configured; set QWEN_API_KEY");
        }
        Instant modelStartedAt = Instant.now();
        try {
            String output = callModel(model, task, snapshot, collected, results);
            traceRepository.model(task.incidentId(), task.taskId(), model.getClass().getSimpleName(),
                    task.agentType().name().toLowerCase() + "-v1", null, null, modelStartedAt, "SUCCEEDED");
            return parseFinding(task, output, collected);
        } catch (RuntimeException exception) {
            traceRepository.model(task.incidentId(), task.taskId(), model.getClass().getSimpleName(),
                    task.agentType().name().toLowerCase() + "-v1", null, null, modelStartedAt, "FAILED");
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(DiagnosticTool<?> tool, ToolExecutionContext context) {
        return ((DiagnosticTool<Map<String, Object>>) tool).execute(Map.of(), context);
    }

    private String callModel(ChatModel model, AgentTask task, IncidentSnapshot snapshot,
                             List<Evidence> evidence, List<ToolResult> results) {
        String system = "You are a restricted FaultPilot specialist agent. " +
                "Use only supplied evidence. Return JSON only with fields: status, causeCode, " +
                "supportingEvidenceIds, counterEvidenceIds, completedChecks, missingChecks, suggestedAgent, summary. " +
                "Do not invent evidence IDs, tools, source locations, or remediation claims. " +
                "When BLOCKING_TASK_FOUND is supplied, cite it when describing the observed class, method, file, line, or blocking operation. " +
                "If evidence is insufficient, use INSUFFICIENT_EVIDENCE.";
        String user = "AgentType=" + task.agentType() + "\nTask=" + task.objective() +
                "\nSnapshot=" + serialize(snapshot) + "\nEvidence=" + serialize(evidence) +
                "\nToolResults=" + serialize(results);
        List<ChatMessage> messages = List.of(SystemMessage.from(system), UserMessage.from(user));
        ChatResponse response = model.chat(ChatRequest.builder().messages(messages).temperature(0.0).maxOutputTokens(800).build());
        return response.aiMessage().text();
    }

    private AgentFinding parseFinding(AgentTask task, String raw, List<Evidence> evidence) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Set<UUID> allowedEvidenceIds = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            return new AgentFinding(task.taskId(), task.agentType(),
                    enumValue(node, "status", com.astrayzjt.faultpilot.common.domain.FindingStatus.class,
                            com.astrayzjt.faultpilot.common.domain.FindingStatus.INSUFFICIENT_EVIDENCE),
                    enumValue(node, "causeCode", com.astrayzjt.faultpilot.common.domain.CauseCode.class,
                            com.astrayzjt.faultpilot.common.domain.CauseCode.UNKNOWN),
                    ids(node, "supportingEvidenceIds").stream().filter(allowedEvidenceIds::contains).toList(),
                    ids(node, "counterEvidenceIds").stream().filter(allowedEvidenceIds::contains).toList(),
                    enums(node, "completedChecks", com.astrayzjt.faultpilot.common.domain.EvidenceType.class),
                    enums(node, "missingChecks", com.astrayzjt.faultpilot.common.domain.EvidenceType.class),
                    enumValue(node, "suggestedAgent", AgentType.class, null), node.path("summary").asText(""));
        } catch (Exception exception) {
            return new AgentFinding(task.taskId(), task.agentType(),
                    com.astrayzjt.faultpilot.common.domain.FindingStatus.INSUFFICIENT_EVIDENCE,
                    com.astrayzjt.faultpilot.common.domain.CauseCode.UNKNOWN, List.of(), List.of(), List.of(),
                    List.of(), null, "Model output could not be parsed as the required Finding JSON");
        }
    }

    private String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in model output");
        }
        return raw.substring(start, end + 1);
    }

    private <T extends Enum<T>> T enumValue(JsonNode node, String name, Class<T> type, T fallback) {
        String value = node.path(name).asText("");
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private List<UUID> ids(JsonNode node, String name) {
        List<UUID> result = new ArrayList<>();
        node.path(name).forEach(value -> {
            try {
                result.add(UUID.fromString(value.asText()));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return List.copyOf(result);
    }

    private <T extends Enum<T>> List<T> enums(JsonNode node, String name, Class<T> type) {
        List<T> result = new ArrayList<>();
        node.path(name).forEach(value -> {
            try {
                result.add(Enum.valueOf(type, value.asText().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
            }
        });
        return List.copyOf(result);
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
