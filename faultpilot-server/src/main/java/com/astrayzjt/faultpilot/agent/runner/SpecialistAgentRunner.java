package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentStepAction;
import com.astrayzjt.faultpilot.common.domain.AgentStepDecision;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.FindingStatus;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.astrayzjt.faultpilot.common.model.ModelOutputInvalidException;
import com.astrayzjt.faultpilot.common.model.RemoteModelUnavailableException;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentStepRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import com.astrayzjt.faultpilot.tool.registry.DiagnosticTool;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
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
    private final IncidentEventService eventService;
    private final long specialistDeadlineSeconds;

    public SpecialistAgentRunner(ToolRegistry toolRegistry, EvidenceService evidenceService,
                                 ObjectMapper objectMapper, RemoteModelClient modelClient,
                                 ToolInvocationGuard invocationGuard, AgentStepRepository stepRepository,
                                 TraceRepository traceRepository, IncidentEventService eventService,
                                 @Value("${faultpilot.agent.specialist-deadline-seconds:120}") long specialistDeadlineSeconds) {
        this.toolRegistry = toolRegistry;
        this.evidenceService = evidenceService;
        this.objectMapper = objectMapper;
        this.modelClient = modelClient;
        this.invocationGuard = invocationGuard;
        this.stepRepository = stepRepository;
        this.traceRepository = traceRepository;
        this.eventService = eventService;
        this.specialistDeadlineSeconds = Math.max(30, Math.min(300, specialistDeadlineSeconds));
    }

    public AgentFinding run(AgentTask task, IncidentSnapshot snapshot, List<Evidence> existingEvidence) {
        Instant deadline = Instant.now().plusSeconds(specialistDeadlineSeconds);
        List<Evidence> collected = new ArrayList<>(existingEvidence);
        List<Observation> observations = new ArrayList<>();
        Set<String> calledTools = new HashSet<>();
        AgentStepDecision lastDecision = null;
        int stepsUsed = 0;
        for (int step = 0; step < task.maxSteps(); step++) {
            if (Instant.now().isAfter(deadline)) {
                break;
            }
            AgentStepDecision decision = decideNext(task, snapshot, collected, observations, calledTools, step);
            stepsUsed = step + 1;
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
        return finish(task, snapshot, collected, observations, lastDecision, stepsUsed);
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
            try {
                return parseDecision(repaired);
            } catch (RuntimeException ignored) {
                throw new ModelOutputInvalidException(ModelRole.SPECIALIST);
            }
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
                "An observation without an evidenceId is operational context only: do not present it as an audited " +
                "fact or use it as supporting or counter evidence. REDIS_SERVER_LATENCY is the catalog cause for " +
                "end-to-end Redis command-path latency; it does not assert Redis process resource saturation. " +
                "When BLOCKING_TASK_FOUND is supplied, cite it when describing the observed class, method, file, line, or blocking operation. " +
                "Return SUCCEEDED when cited direct evidence supports a candidate cause, even when a secondary " +
                "corroborating check is unavailable; EvidenceGate makes the final confidence decision. " +
                "Use INSUFFICIENT_EVIDENCE only when no cited direct evidence supports a cause.";
        String user = "AgentType=" + task.agentType() + "\nTask=" + task.objective() +
                "\nSnapshot=" + serialize(snapshot) + "\nEvidence=" + serialize(evidence) +
                "\nObservations=" + serialize(observations) + "\nLastDecision=" + serialize(lastDecision);
        String raw;
        try {
            raw = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                    task.agentType().name().toLowerCase() + "-finding-v2", system, user, 800);
        } catch (RemoteModelUnavailableException exception) {
            return fallbackFinding(task, evidence, stepsUsed, "Remote finding call was unavailable");
        }
        try {
            return parseFinding(task, raw, evidence, stepsUsed);
        } catch (RuntimeException exception) {
            Set<UUID> allowedEvidenceIds = evidence.stream().map(Evidence::evidenceId)
                    .collect(java.util.stream.Collectors.toSet());
            try {
                String repaired = modelClient.complete(task.incidentId(), task.taskId(), ModelRole.SPECIALIST,
                        task.agentType().name().toLowerCase() + "-finding-repair-v2",
                        "Repair the draft into one JSON object only. Required fields: " +
                                "status, causeCode, supportingEvidenceIds, counterEvidenceIds, completedChecks, " +
                                "missingChecks, suggestedAgent, summary. " +
                                "Allowed status values: " + java.util.Arrays.toString(FindingStatus.values()) + ". " +
                                "Allowed causeCode values: " + java.util.Arrays.toString(CauseCode.values()) + ". " +
                                "suggestedAgent must be one of " + java.util.Arrays.toString(AgentType.values()) + " or null. " +
                                "Evidence ID arrays may contain only IDs from AllowedEvidenceIds. Never invent an ID.",
                        "Draft=" + raw + "\nAllowedEvidenceIds=" + serialize(allowedEvidenceIds), 800);
                return parseFinding(task, repaired, evidence, stepsUsed);
            } catch (RuntimeException ignored) {
                return fallbackFinding(task, evidence, stepsUsed,
                        ignored instanceof RemoteModelUnavailableException
                                ? "Remote finding repair call was unavailable"
                                : "Both constrained finding responses were invalid");
            }
        }
    }

    private AgentFinding fallbackFinding(AgentTask task, List<Evidence> evidence, int stepsUsed, String reason) {
        eventService.append(task.incidentId(), "SPECIALIST_OUTPUT_FALLBACK", Map.of(
                "taskId", task.taskId().toString(),
                "agent", task.agentType().name(),
                "evidenceCount", evidence.size(),
                "reason", reason));
        return safeFallbackFinding(task, evidence, stepsUsed);
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
            List<UUID> supporting = allowedIds(node, "supportingEvidenceIds", allowedEvidenceIds);
            List<UUID> counter = allowedIds(node, "counterEvidenceIds", allowedEvidenceIds);
            FindingStatus status = findingStatus(node.path("status").asText(""));
            CauseCode causeCode = causeCode(node.path("causeCode").asText(""));
            if (status == FindingStatus.SUCCEEDED && (causeCode == CauseCode.UNKNOWN || supporting.isEmpty())) {
                status = FindingStatus.INSUFFICIENT_EVIDENCE;
            }
            return new AgentFinding(task.taskId(), task.agentType(), status, causeCode, supporting, counter,
                    enums(node, "completedChecks", EvidenceType.class),
                    enums(node, "missingChecks", EvidenceType.class),
                    optionalAgent(node, "suggestedAgent"), node.path("summary").asText(""),
                    List.of(), node.path("handoffReason").asText(""), stepsUsed);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Model output is not a valid specialist Finding", exception);
        }
    }

    private AgentFinding safeFallbackFinding(AgentTask task, List<Evidence> evidence, int stepsUsed) {
        List<EvidenceType> completedChecks = evidence.stream().map(Evidence::type)
                .filter(type -> type != EvidenceType.DATA_UNAVAILABLE).distinct().toList();
        return new AgentFinding(task.taskId(), task.agentType(), FindingStatus.INSUFFICIENT_EVIDENCE,
                CauseCode.UNKNOWN, List.of(), List.of(), completedChecks, List.of(), null,
                "The specialist preserved collected evidence after its final structured response failed validation",
                List.of(), "", stepsUsed);
    }

    private FindingStatus findingStatus(String raw) {
        String value = normalizeEnum(raw);
        return switch (value) {
            case "SUCCEEDED", "SUCCESS", "COMPLETED", "CONFIRMED", "SUPPORTED" -> FindingStatus.SUCCEEDED;
            case "OUT_OF_SCOPE" -> FindingStatus.OUT_OF_SCOPE;
            case "TIMED_OUT", "TIMEOUT" -> FindingStatus.TIMED_OUT;
            case "FAILED", "FAILURE" -> FindingStatus.FAILED;
            default -> FindingStatus.INSUFFICIENT_EVIDENCE;
        };
    }

    private CauseCode causeCode(String raw) {
        String value = normalizeEnum(raw);
        try {
            return CauseCode.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return switch (value) {
                case "CPU_HOTSPOT", "JVM_CPU_HIGH", "PROCESS_CPU_HIGH" -> CauseCode.JVM_CPU_HOTSPOT;
                case "THREAD_POOL_EXHAUSTED", "JVM_THREAD_POOL_SATURATION", "THREAD_POOL_ACTIVE_AT_MAX",
                        "THREAD_POOL_QUEUE_GROWING" -> CauseCode.JVM_THREAD_POOL_EXHAUSTED;
                case "SLOW_SQL", "SLOW_SQL_FOUND", "DATABASE_SLOW_QUERY" -> CauseCode.DB_SLOW_QUERY;
                case "DB_CONNECTION_POOL_EXHAUSTED", "DATABASE_CONNECTION_POOL_EXHAUSTED",
                        "DATABASE_POOL_EXHAUSTED", "DB_POOL_ACTIVE_AT_MAX", "DB_POOL_PENDING_HIGH" -> CauseCode.DB_POOL_EXHAUSTED;
                case "DOWNSTREAM_TIMEOUT", "DOWNSTREAM_LATENCY_HIGH", "DEPENDENCY_LATENCY_HIGH" -> CauseCode.DEPENDENCY_TIMEOUT;
                case "REDIS_LATENCY", "REDIS_LATENCY_HIGH", "REDIS_COMMAND_LATENCY_HIGH", "REDIS_SERVER_SLOW",
                        "REDIS_SERVER_LATENCY_HIGH", "CACHE_SERVER_LATENCY", "CACHE_LATENCY_HIGH",
                        "CACHE_COMMAND_LATENCY_HIGH", "HIGH_REDIS_LATENCY" -> CauseCode.REDIS_SERVER_LATENCY;
                case "CACHE_CLIENT_POOL_EXHAUSTED", "CACHE_CLIENT_POOL_SATURATED", "REDIS_POOL_EXHAUSTED",
                        "REDIS_CLIENT_POOL_SATURATED", "REDIS_CLIENT_POOL_PENDING_HIGH",
                        "REDIS_CLIENT_CONNECTION_POOL_EXHAUSTED" -> CauseCode.REDIS_CLIENT_POOL_EXHAUSTED;
                default -> CauseCode.UNKNOWN;
            };
        }
    }

    private String normalizeEnum(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
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

    private List<UUID> allowedIds(JsonNode node, String name, Set<UUID> allowed) {
        return ids(node, name).stream().filter(allowed::contains).distinct().toList();
    }

    private AgentType optionalAgent(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank() || Set.of("NULL", "NONE", "N/A", "NOT_APPLICABLE").contains(value.toUpperCase())) {
            return null;
        }
        try {
            return AgentType.valueOf(normalizeEnum(value));
        } catch (IllegalArgumentException exception) {
            return null;
        }
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
