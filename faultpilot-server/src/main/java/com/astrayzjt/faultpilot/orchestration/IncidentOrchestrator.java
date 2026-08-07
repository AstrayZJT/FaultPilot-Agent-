package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.action.RemediationService;
import com.astrayzjt.faultpilot.common.domain.AgentFinding;
import com.astrayzjt.faultpilot.common.domain.AgentTask;
import com.astrayzjt.faultpilot.common.domain.AgentTaskStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisPolicy;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisRepository;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.incident.persistence.IncidentRepository;
import com.astrayzjt.faultpilot.orchestration.persistence.AgentTaskRepository;
import com.astrayzjt.faultpilot.triage.BaselineCollector;
import com.astrayzjt.faultpilot.triage.RoutingAdvisor;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
public class IncidentOrchestrator {

    private static final int MAX_ROUNDS = 2;
    private static final int DEFAULT_AGENT_MAX_STEPS = 4;
    private static final int JVM_AGENT_MAX_STEPS = 4;

    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final SupervisorPlanner planner;
    private final PlanValidator planValidator;
    private final Map<AgentType, SpecialistAgent> agents;
    private final AgentTaskRepository taskRepository;
    private final EvidenceService evidenceService;
    private final BaselineCollector baselineCollector;
    private final RoutingAdvisor routingAdvisor;
    private final DiagnosisPolicy diagnosisPolicy;
    private final DiagnosisRepository diagnosisRepository;
    private final IncidentEventService eventService;
    private final RemediationService remediationService;
    private final Executor orchestratorExecutor;
    private final Executor specialistExecutor;
    private final CompiledGraph<IncidentGraphState> graph;

    public IncidentOrchestrator(IncidentService incidentService, IncidentRepository incidentRepository,
                                SupervisorPlanner planner, PlanValidator planValidator, List<SpecialistAgent> agents,
                                AgentTaskRepository taskRepository, EvidenceService evidenceService,
                                BaselineCollector baselineCollector, RoutingAdvisor routingAdvisor,
                                DiagnosisPolicy diagnosisPolicy, DiagnosisRepository diagnosisRepository,
                                IncidentEventService eventService, RemediationService remediationService, DataSource dataSource,
                                @Qualifier("orchestratorExecutor") Executor orchestratorExecutor,
                                @Qualifier("specialistAgentExecutor") Executor specialistExecutor) throws Exception {
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.planner = planner;
        this.planValidator = planValidator;
        EnumMap<AgentType, SpecialistAgent> indexed = new EnumMap<>(AgentType.class);
        agents.forEach(agent -> indexed.put(agent.type(), agent));
        this.agents = Map.copyOf(indexed);
        this.taskRepository = taskRepository;
        this.evidenceService = evidenceService;
        this.baselineCollector = baselineCollector;
        this.routingAdvisor = routingAdvisor;
        this.diagnosisPolicy = diagnosisPolicy;
        this.diagnosisRepository = diagnosisRepository;
        this.eventService = eventService;
        this.remediationService = remediationService;
        this.orchestratorExecutor = orchestratorExecutor;
        this.specialistExecutor = specialistExecutor;
        this.graph = buildGraph(dataSource);
    }

    public void start(UUID incidentId) {
        try {
            orchestratorExecutor.execute(() -> runGraph(incidentId));
        } catch (RejectedExecutionException exception) {
            incidentService.updateStatus(incidentId, IncidentStatus.FAILED);
            eventService.append(incidentId, "ORCHESTRATOR_REJECTED", Map.of("reason", "executor capacity exceeded"));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingIncidents() {
        Set<IncidentStatus> recoverable = Set.of(IncidentStatus.ACCEPTED, IncidentStatus.INVESTIGATING,
                IncidentStatus.REMEDIATING, IncidentStatus.VERIFYING);
        incidentRepository.findIdsByStatus(recoverable).forEach(id -> {
            taskRepository.interruptRunning(id);
            start(id);
        });
    }

    private void runGraph(UUID incidentId) {
        RunnableConfig config = RunnableConfig.builder().threadId(incidentId.toString()).build();
        try {
            boolean resumable = graph.lastStateOf(config).isPresent();
            if (resumable) {
                graph.invoke(GraphInput.resume(), config);
            } else {
                graph.invoke(Map.of("incidentId", incidentId.toString(), "round", 0,
                        "plannedAgents", List.of(), "outcome", "FOLLOW_UP"), config);
            }
        } catch (RuntimeException exception) {
            incidentService.updateStatus(incidentId, IncidentStatus.FAILED);
            eventService.append(incidentId, "ORCHESTRATION_FAILED",
                    Map.of("error", exception.getClass().getSimpleName(), "message", safeMessage(exception)));
        }
    }

    private CompiledGraph<IncidentGraphState> buildGraph(DataSource dataSource) throws Exception {
        ObjectStreamStateSerializer<IncidentGraphState> serializer =
                new ObjectStreamStateSerializer<>(IncidentGraphState::new);
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource).stateSerializer(serializer)
                .createTables(true).dropTablesFirst(false).build();
        Map<String, org.bsc.langgraph4j.state.Channel<?>> channels = new LinkedHashMap<>();
        channels.put("incidentId", Channels.base(() -> ""));
        channels.put("round", Channels.base(() -> 0));
        channels.put("plannedAgents", Channels.base((java.util.function.Supplier<List<String>>) List::of));
        channels.put("outcome", Channels.base(() -> "FOLLOW_UP"));
        StateGraph<IncidentGraphState> stateGraph = new StateGraph<>(channels, serializer);
        stateGraph.addNode("load_incident", node_async(this::loadIncidentNode));
        stateGraph.addNode("collect_baseline", node_async(this::collectBaselineNode));
        stateGraph.addNode("supervisor_plan", node_async(this::supervisorNode));
        stateGraph.addNode("dispatch_agents", node_async(this::dispatchNode));
        stateGraph.addNode("evaluate_evidence", node_async(this::evaluateNode));
        stateGraph.addEdge(START, "load_incident");
        stateGraph.addEdge("load_incident", "collect_baseline");
        stateGraph.addEdge("collect_baseline", "supervisor_plan");
        stateGraph.addEdge("supervisor_plan", "dispatch_agents");
        stateGraph.addEdge("dispatch_agents", "evaluate_evidence");
        stateGraph.addConditionalEdges("evaluate_evidence", edge_async(state ->
                        "FOLLOW_UP".equals(state.outcome()) && state.round() < MAX_ROUNDS ? "follow_up" : "done"),
                Map.of("follow_up", "supervisor_plan", "done", END));
        return stateGraph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false)
                .recursionLimit(30).graphId("faultpilot-incident-v1").build());
    }

    private Map<String, Object> loadIncidentNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        incidentService.updateStatus(incident.incidentId(), IncidentStatus.INVESTIGATING);
        eventService.append(incident.incidentId(), "INVESTIGATION_STARTED",
                Map.of("serviceName", incident.snapshot().serviceName()));
        return Map.of();
    }

    private Map<String, Object> supervisorNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        int round = state.round() + 1;
        List<com.astrayzjt.faultpilot.common.domain.Evidence> evidence = evidenceService.findByIncident(state.incidentId());
        InvestigationPlan plan = planValidator.validate(
                planner.plan(incident.snapshot(), evidence, routingAdvisor.derive(evidence), round), evidence);
        List<String> plannedAgents = plan.tasks().stream().map(task -> task.agentType().name()).toList();
        eventService.append(state.incidentId(), "INVESTIGATION_PLANNED",
                Map.of("round", round, "agents", plannedAgents, "reason", plan.reason()));
        return Map.of("round", round, "plannedAgents", plannedAgents, "outcome", "DISPATCHING");
    }

    private Map<String, Object> collectBaselineNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        List<com.astrayzjt.faultpilot.common.domain.Evidence> evidence = baselineCollector.collect(incident);
        eventService.append(state.incidentId(), "BASELINE_COLLECTED", Map.of(
                "evidenceIds", evidence.stream().map(item -> item.evidenceId().toString()).toList(),
                "count", evidence.size()));
        eventService.append(state.incidentId(), "ROUTING_SIGNALS_COMPUTED", Map.of(
                "signals", routingAdvisor.derive(evidenceService.findByIncident(state.incidentId()))));
        return Map.of();
    }

    private Map<String, Object> dispatchNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        List<CompletableFuture<AgentFinding>> futures = state.plannedAgents().stream()
                .map(AgentType::valueOf).map(type -> dispatch(type, incident, state.round())).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        long completed = futures.stream().filter(future -> !future.isCompletedExceptionally()).count();
        eventService.append(state.incidentId(), "AGENTS_COMPLETED",
                Map.of("round", state.round(), "completed", completed, "requested", futures.size()));
        return Map.of("outcome", "EVALUATING");
    }

    private Map<String, Object> evaluateNode(IncidentGraphState state) {
        DiagnosisDecision decision = diagnosisPolicy.evaluate(evidenceService.findByIncident(state.incidentId()));
        diagnosisRepository.save(state.incidentId(), decision);
        if (decision.status() == DiagnosisStatus.CONFIRMED) {
            Incident incident = incidentService.find(state.incidentId()).orElseThrow();
            if (incident.snapshot().allowRemediation() && remediationService.isEnabled()) {
                remediationService.prepare(state.incidentId());
            } else {
                incidentService.updateStatus(state.incidentId(), IncidentStatus.DIAGNOSED);
                if (incident.snapshot().allowRemediation()) {
                    eventService.append(state.incidentId(), "ACTION_SKIPPED",
                            Map.of("reason", "Remediation is disabled outside LAB mode"));
                }
            }
            eventService.append(state.incidentId(), "DIAGNOSIS_COMPLETED", decision);
            return Map.of("outcome", "DIAGNOSED");
        }
        if (state.round() < MAX_ROUNDS) {
            eventService.append(state.incidentId(), "FOLLOW_UP_REQUESTED",
                    Map.of("round", state.round(), "missingEvidence", decision.missingEvidenceTypes()));
            return Map.of("outcome", "FOLLOW_UP");
        }
        incidentService.updateStatus(state.incidentId(), IncidentStatus.INCONCLUSIVE);
        eventService.append(state.incidentId(), "DIAGNOSIS_INCONCLUSIVE", decision);
        return Map.of("outcome", "INCONCLUSIVE");
    }

    private CompletableFuture<AgentFinding> dispatch(AgentType type, Incident incident, int round) {
        AgentTask task = new AgentTask(UUID.randomUUID(), incident.incidentId(),
                type.name().toLowerCase() + "-round-" + round, type,
                "Investigate " + incident.snapshot().serviceName() + " incident", maxSteps(type), round,
                AgentTaskStatus.PENDING, null, null);
        taskRepository.insert(task);
        return CompletableFuture.supplyAsync(() -> {
            taskRepository.markRunning(task);
            try {
                AgentFinding finding = agents.get(type).investigate(task, incident.snapshot(),
                        evidenceService.findByIncident(incident.incidentId()));
                AgentTaskStatus status = switch (finding.status()) {
                    case SUCCEEDED -> AgentTaskStatus.SUCCEEDED;
                    case OUT_OF_SCOPE -> AgentTaskStatus.OUT_OF_SCOPE;
                    case INSUFFICIENT_EVIDENCE -> AgentTaskStatus.INSUFFICIENT_EVIDENCE;
                    case TIMED_OUT -> AgentTaskStatus.TIMED_OUT;
                    case FAILED -> AgentTaskStatus.FAILED;
                };
                taskRepository.complete(task, status, finding, null);
                return finding;
            } catch (RuntimeException exception) {
                taskRepository.complete(task, AgentTaskStatus.FAILED, null, safeMessage(exception));
                throw exception;
            }
        }, specialistExecutor);
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }

    private int maxSteps(AgentType type) {
        return type == AgentType.JVM_AGENT ? JVM_AGENT_MAX_STEPS : DEFAULT_AGENT_MAX_STEPS;
    }
}
