package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentStepAction;
import com.astrayzjt.faultpilot.common.domain.AgentStepDecision;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentStepRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SpecialistAgentRunner {

    private final ToolRegistry toolRegistry;
    private final EvidenceService evidenceService;
    private final ObjectMapper objectMapper;
    private final RemoteModelClient modelClient;
    private final ToolInvocationGuard invocationGuard;
    private final AgentStepRepository stepRepository;
    private final TraceRepository traceRepository;

    public SpecialistAgentRunner(ToolRegistry toolRegistry, EvidenceService evidenceService,
                                 ObjectMapper objectMapper, RemoteModelClient modelClient,
                                 ToolInvocationGuard invocationGuard, AgentStepRepository stepRepository,
                                 TraceRepository traceRepository) {
        this.toolRegistry = toolRegistry;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
        this.invocationGuard = invocationGuard;
        this.stepRepository = stepRepository;
        this.traceRepository = traceRepository;
    }

    public AgentFinding run(AgentTask task, IncidentSnapshot snapshot, List<Evidence> existingEvidence) {
        Instant deadline = Instant.now().plusSeconds(30);
        List<Evidence> collected = new ArrayList<>(existingEvidence);
        List<Observation> observations = new ArrayList<>();
        Set<String> calledTools = new HashSet<>();
        AgentStepDecision lastDecision = null;
        for (int step = 0; step < task.maxSteps(); step++) {
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            AgentStepDecision decision = decideNext(task, snapshot, collected, observations, calledTools, step);
            lastDecision = decision;
            UUID stepId = stepRepository.recordDecision(task.taskId(), step, decision, "DECIDED");
            if (decision.action() != AgentStepAction.CALL_TOOL) {
                break;
            }
            ToolResult result = invokeTool(task, snapshot, deadline, decision, calledTools, stepId);
            Evidence evidence = evidenceService.record(task.incidentId(), task.taskId(), result,
                    snapshot.timeRange().start(), snapshot.timeRange().end());
            UUID evidenceId = evidence == null ? null : evidence.evidenceId();
            if (evidence != null && collected.stream().noneMatch(item -> item.evidenceId().equals(evidence.evidenceId()))) {
                collected.add(evidence);
            }
            if (evidenceId != null) {
                evidenceService.linkTaskEvidence(task.taskId(), evidenceId, "PRODUCED");
            }
            stepRepository.attachEvidence(stepId, evidenceId, result.success() ? "SUCCEEDED" : "FAILED");
            observations.add(new Observation(decision.toolName(), result.success(), result.summary(), evidenceId));
        }
        return finish(task, snapshot, collected, observations, lastDecision, task.maxSteps());
    }

    private AgentStepDecision decideNext(AgentTask task, IncidentSnapshot snapshot, List<Evidence> evidence,
                                         List<Observation> observations, Set<String> calledTools, int step) {
        String system = "You are a restricted FaultPilot specialist Agent. " +
                "Choose exactly one next action from CALL_TOOL, COMPLETE, HANDOFF. " +
                "You may select only a tool listed for your AgentType. Tool arguments must be {}. " +
                "Use only supplied Evidence and observations. Never invent IDs or facts. " +
                "Return JSON only: {action,toolName,arguments,evidenceIds,suggestedAgent,decisionSummary}.";
        String user = "AgentType=" + task.agentType() + "\nstep=" + step + "\nTask=" + task.objective() +
                "\nSnapshot=" + serialize(snapshot) + "\nAvailableTools=" + serialize(toolRegistry.names(task.agentType())) +
                "\nCalledTools=" + serialize(calledTools) + "\nEvidence=" + serialize(evidence) +
                "\nObservations=" + serialize(observations);
        String raw = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                task.agentType().name().toLowerCase() + "-step-v2", system, user, 500);
        try {
            return parseDecision(raw);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                    task.agentType().name().toLowerCase() + "-step-repair-v2",
                    "Return only a valid JSON object matching the requested AgentStepDecision schema. Do not add commentary.",
                    raw, 500);
            return parseDecision(repaired);
        }
    }

    private ToolResult invokeTool(AgentTask task, IncidentSnapshot snapshot, Instant deadline,
                                  AgentStepDecision decision, Set<String> calledTools, UUID stepId) {
        invocationGuard.validate(decision, task.agentType(), calledTools);
        DiagnosticTool<?> tool = toolRegistry.require(decision.toolName(), task.agentType());
        ToolExecutionContext context = new ToolExecutionContext(task.incidentId(), task.taskId(),
                task.agentType(), snapshot.serviceName(), deadline);
        Instant toolStartedAt = Instant.now();
        try {
            ToolResult result = execute(tool, decision.arguments(), context);
            traceRepository.tool(task.incidentId(), task.taskId(), task.agentType().name(), tool.name(),
                    argumentsHash(decision), result.success() ? "SUCCEEDED" : "FAILED", result.summary(), null,
                    toolStartedAt, Instant.now());
            return result;
        } catch (RuntimeException exception) {
            traceRepository.tool(task.incidentId(), task.taskId(), task.agentType().name(), tool.name(),
                    argumentsHash(decision), "FAILED", null, exception.getClass().getSimpleName(), toolStartedAt, Instant.now());
            stepRepository.attachEvidence(stepId, null, "FAILED");
            throw exception;
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResult execute(DiagnosticTool<?> tool, JsonNode arguments, ToolExecutionContext context) {
        Object value = objectMapper.convertValue(arguments == null ? objectMapper.createObjectNode() : arguments,
                tool.argumentType());
        return ((DiagnosticTool<Object>) tool).execute(value, context);
    }

    private AgentFinding finish(AgentTask task, IncidentSnapshot snapshot, List<Evidence> evidence,
                                List<Observation> observations, AgentStepDecision lastDecision, int stepsUsed) {
        String system = "You are a restricted FaultPilot specialist agent. " +
                "Use only supplied evidence. Return JSON only with fields: status, causeCode, " +
                "supportingEvidenceIds, counterEvidenceIds, completedChecks, missingChecks, suggestedAgent, summary. " +
                "Do not invent evidence IDs, tools, source locations, or remediation claims. " +
                "When BLOCKING_TASK_FOUND is supplied, cite it when describing the observed class, method, file, line, or blocking operation. " +
                "If evidence is insufficient, use INSUFFICIENT_EVIDENCE.";
        String user = "AgentType=" + task.agentType() + "\nTask=" + task.objective() +
                "\nSnapshot=" + serialize(snapshot) + "\nEvidence=" + serialize(evidence) +
                "\nObservations=" + serialize(observations) + "\nLastDecision=" + serialize(lastDecision);
        String raw = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                task.agentType().name().toLowerCase() + "-finding-v2", system, user, 800);
        try {
            return parseFinding(task, raw, evidence, stepsUsed);
        } catch (RuntimeException exception) {
            String repaired = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                    task.agentType().name().toLowerCase() + "-finding-repair-v2",
                    "Return only a valid JSON object matching the requested specialist finding schema. Do not add commentary.",
                    raw, 800);
            return parseFinding(task, repaired, evidence, stepsUsed);
        }
    }

    private AgentStepDecision parseDecision(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            String action = node.path("action").asText("").toUpperCase();
            AgentStepAction parsedAction;
            try {
                parsedAction = AgentStepAction.valueOf(action);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Invalid AgentStepDecision action", exception);
            }
            return new AgentStepDecision(parsedAction, node.path("toolName").asText(""),
                    node.has("arguments") ? node.get("arguments") : objectMapper.createObjectNode(),
                    ids(node, "evidenceIds"), optionalAgent(node, "suggestedAgent"), node.path("decisionSummary").asText(""));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("AgentStepDecision is not valid JSON", exception);
        }
    }

    private AgentFinding parseFinding(AgentTask task, String raw, List<Evidence> evidence, int stepsUsed) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            Set<UUID> allowedEvidenceIds = evidence.stream().map(Evidence::evidenceId).collect(java.util.stream.Collectors.toSet());
            List<UUID> supporting = strictIds(node, "supportingEvidenceIds", allowedEvidenceIds);
            List<UUID> counter = strictIds(node, "counterEvidenceIds", allowedEvidenceIds);
            return new AgentFinding(task.taskId(), task.agentType(),
                    enumValue(node, "status", com.astrayzjt.faultpilot.common.domain.FindingStatus.class,
                            null),
                    enumValue(node, "causeCode", com.astrayzjt.faultpilot.common.domain.CauseCode.class,
                            null), supporting, counter,
                    enums(node, "completedChecks", com.astrayzjt.faultpilot.common.domain.EvidenceType.class),
                    enums(node, "missingChecks", com.astrayzjt.faultpilot.common.domain.EvidenceType.class),
                    optionalAgent(node, "suggestedAgent"), node.path("summary").asText(""),
                    List.of(), node.path("handoffReason").asText(""), stepsUsed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Model output is not a valid specialist Finding", exception);
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
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("Invalid enum value for " + name);
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

    private List<UUID> strictIds(JsonNode node, String name, Set<UUID> allowed) {
        List<UUID> values = ids(node, name);
        if (!allowed.containsAll(values) || values.size() != node.path(name).size()) {
            throw new IllegalArgumentException("Finding contains an invalid " + name + " reference");
        }
        return values;
    }

    private AgentType optionalAgent(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank() || "NULL".equalsIgnoreCase(value)) {
            return null;
        }
        return AgentType.valueOf(value.toUpperCase());
    }

    private String argumentsHash(AgentStepDecision decision) {
        return Integer.toHexString(serialize(decision.arguments()).hashCode());
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

    private record Observation(String toolName, boolean success, String summary, UUID evidenceId) {
    }
}
