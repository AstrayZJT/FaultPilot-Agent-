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
import com.astrayzjt.faultpilot.diagnosis.DiagnosisCritic;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisCritiqueRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisProposalRepository;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisSynthesizer;
import com.astrayzjt.faultpilot.diagnosis.EvidenceGate;
import com.astrayzjt.faultpilot.diagnosis.EvidenceGateRepository;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.common.model.ModelInteractionException;
import com.astrayzjt.faultpilot.common.model.ModelOutputInvalidException;
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
    private final DiagnosisSynthesizer diagnosisSynthesizer;
    private final DiagnosisCritic diagnosisCritic;
    private final EvidenceGate evidenceGate;
    private final DiagnosisProposalRepository proposalRepository;
    private final DiagnosisCritiqueRepository critiqueRepository;
    private final EvidenceGateRepository gateRepository;
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
                                DiagnosisSynthesizer diagnosisSynthesizer, DiagnosisCritic diagnosisCritic,
                                EvidenceGate evidenceGate, DiagnosisProposalRepository proposalRepository,
                                DiagnosisCritiqueRepository critiqueRepository, EvidenceGateRepository gateRepository,
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
        this.diagnosisSynthesizer = diagnosisSynthesizer;
        this.diagnosisCritic = diagnosisCritic;
        this.evidenceGate = evidenceGate;
        this.proposalRepository = proposalRepository;
        this.critiqueRepository = critiqueRepository;
        this.gateRepository = gateRepository;
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
            ModelInteractionException modelFailure = findModelFailure(exception);
            if (modelFailure != null) {
                incidentService.updateStatus(incidentId, IncidentStatus.INCONCLUSIVE);
                if (modelFailure instanceof ModelOutputInvalidException invalidOutput) {
                    eventService.append(incidentId, "MODEL_OUTPUT_INVALID", Map.of("role", invalidOutput.role().name()));
                }
                eventService.append(incidentId, "DIAGNOSIS_INCONCLUSIVE", Map.of(
                        "reason", "A required remote model role was unavailable or returned an invalid constrained response",
                        "modelFailure", modelFailure.getClass().getSimpleName()));
                return;
            }
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
        channels.put("proposalId", Channels.base(() -> ""));
        channels.put("critiqueId", Channels.base(() -> ""));
        channels.put("revision", Channels.base(() -> 0));
        channels.put("critiqueVerdict", Channels.base(() -> ""));
        channels.put("gateStatus", Channels.base(() -> ""));
        StateGraph<IncidentGraphState> stateGraph = new StateGraph<>(channels, serializer);
        stateGraph.addNode("load_incident", node_async(this::loadIncidentNode));
        stateGraph.addNode("collect_baseline", node_async(this::collectBaselineNode));
        stateGraph.addNode("supervisor_plan", node_async(this::supervisorNode));
        stateGraph.addNode("dispatch_agents", node_async(this::dispatchNode));
        stateGraph.addNode("synthesize_diagnosis", node_async(this::synthesizeNode));
        stateGraph.addNode("critique_diagnosis", node_async(this::critiqueNode));
        stateGraph.addNode("revise_diagnosis", node_async(this::reviseNode));
        stateGraph.addNode("evidence_gate", node_async(this::gateNode));
        stateGraph.addEdge(START, "load_incident");
        stateGraph.addEdge("load_incident", "collect_baseline");
        stateGraph.addEdge("collect_baseline", "supervisor_plan");
        stateGraph.addEdge("supervisor_plan", "dispatch_agents");
        stateGraph.addEdge("dispatch_agents", "synthesize_diagnosis");
        stateGraph.addEdge("synthesize_diagnosis", "critique_diagnosis");
        stateGraph.addConditionalEdges("critique_diagnosis", edge_async(state ->
                        "REVISE".equals(state.critiqueVerdict()) && state.revision() == 0 ? "revise" : "gate"),
                Map.of("revise", "revise_diagnosis", "gate", "evidence_gate"));
        stateGraph.addEdge("revise_diagnosis", "critique_diagnosis");
        stateGraph.addConditionalEdges("evidence_gate", edge_async(state ->
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
        List<AgentFinding> findings = taskRepository.findFindingsByIncident(state.incidentId());
        com.astrayzjt.faultpilot.common.domain.DiagnosisCritique latestCritique = latestCritique(state.incidentId());
        InvestigationPlan plan = planValidator.validate(
                planner.plan(incident.snapshot(), evidence, routingAdvisor.derive(evidence), findings, latestCritique, round), evidence);
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

    private Map<String, Object> synthesizeNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        List<com.astrayzjt.faultpilot.common.domain.Evidence> evidence = evidenceService.findByIncident(state.incidentId());
        var proposal = diagnosisSynthesizer.propose(incident.snapshot(), evidence,
                taskRepository.findFindingsByIncident(state.incidentId()), routingAdvisor.derive(evidence),
                state.round(), 0, null);
        proposalRepository.save(proposal);
        eventService.append(state.incidentId(), "DIAGNOSIS_PROPOSED", Map.of(
                "proposalId", proposal.proposalId().toString(), "status", proposal.status(),
                "primaryCause", proposal.primaryCause(), "supportingEvidenceIds", proposal.supportingEvidenceIds()));
        return Map.of("proposalId", proposal.proposalId().toString(), "revision", proposal.revision(), "outcome", "CRITIQUING");
    }

    private Map<String, Object> critiqueNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        var proposal = proposalRepository.find(state.proposalId()).orElseThrow();
        var critique = diagnosisCritic.review(incident.snapshot(), proposal,
                evidenceService.findByIncident(state.incidentId()), taskRepository.findFindingsByIncident(state.incidentId()));
        critiqueRepository.save(critique);
        eventService.append(state.incidentId(), "DIAGNOSIS_CRITIQUED", Map.of(
                "proposalId", proposal.proposalId().toString(), "critiqueId", critique.critiqueId().toString(),
                "verdict", critique.verdict(), "issues", critique.issues()));
        return Map.of("critiqueId", critique.critiqueId().toString(), "critiqueVerdict", critique.verdict().name(), "outcome", "GATING");
    }

    private Map<String, Object> reviseNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        var previous = proposalRepository.find(state.proposalId()).orElseThrow();
        var critique = critiqueRepository.find(state.critiqueId()).orElseThrow();
        var revised = diagnosisSynthesizer.propose(incident.snapshot(), evidenceService.findByIncident(state.incidentId()),
                taskRepository.findFindingsByIncident(state.incidentId()), routingAdvisor.derive(evidenceService.findByIncident(state.incidentId())),
                state.round(), previous.revision() + 1, critique);
        proposalRepository.save(revised);
        eventService.append(state.incidentId(), "DIAGNOSIS_REVISED", Map.of(
                "previousProposalId", previous.proposalId().toString(), "proposalId", revised.proposalId().toString(),
                "revision", revised.revision()));
        return Map.of("proposalId", revised.proposalId().toString(), "revision", revised.revision(), "outcome", "CRITIQUING");
    }

    private Map<String, Object> gateNode(IncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        var proposal = proposalRepository.find(state.proposalId()).orElseThrow();
        var critique = critiqueRepository.find(state.critiqueId()).orElseThrow();
        var gate = evidenceGate.evaluate(proposal, critique, evidenceService.findByIncident(state.incidentId()));
        gateRepository.save(proposal.proposalId(), critique.critiqueId(), gate);
        DiagnosisDecision decision = evidenceGate.toDecision(gate, proposal.contributingFactors());
        diagnosisRepository.save(state.incidentId(), decision);
        eventService.append(state.incidentId(), "EVIDENCE_GATE_DECIDED", Map.of(
                "status", gate.status(), "primaryCause", gate.primaryCause(), "missingEvidenceTypes", gate.missingEvidenceTypes(),
                "rejectionReasons", gate.rejectionReasons()));
        if (gate.status() == DiagnosisStatus.CONFIRMED || gate.status() == DiagnosisStatus.SUPPORTED) {
            incidentService.updateStatus(state.incidentId(), IncidentStatus.DIAGNOSED);
            if (gate.status() == DiagnosisStatus.CONFIRMED && incident.snapshot().allowRemediation()
                    && remediationService.canPrepare(decision)) {
                remediationService.prepare(state.incidentId());
            } else if (incident.snapshot().allowRemediation()) {
                eventService.append(state.incidentId(), "ACTION_SKIPPED", Map.of(
                        "reason", gate.status() == DiagnosisStatus.SUPPORTED ? "Diagnosis is supported but not confirmed" :
                                remediationService.unavailableReason(decision)));
            }
            eventService.append(state.incidentId(), "DIAGNOSIS_COMPLETED", decision);
            return Map.of("outcome", "DIAGNOSED", "gateStatus", gate.status().name());
        }
        boolean followUp = state.round() < MAX_ROUNDS && hasFollowUp(proposal, critique, gate);
        if (followUp) {
            eventService.append(state.incidentId(), "FOLLOW_UP_REQUESTED", Map.of(
                    "round", state.round(), "missingEvidence", gate.missingEvidenceTypes(), "reasons", gate.rejectionReasons()));
            return Map.of("outcome", "FOLLOW_UP", "gateStatus", gate.status().name());
        }
        incidentService.updateStatus(state.incidentId(), IncidentStatus.INCONCLUSIVE);
        eventService.append(state.incidentId(), "DIAGNOSIS_INCONCLUSIVE", decision);
        return Map.of("outcome", "INCONCLUSIVE", "gateStatus", gate.status().name());
    }

    private boolean hasFollowUp(com.astrayzjt.faultpilot.common.domain.DiagnosisProposal proposal,
                                com.astrayzjt.faultpilot.common.domain.DiagnosisCritique critique,
                                com.astrayzjt.faultpilot.common.domain.EvidenceGateResult gate) {
        return !proposal.requestedFollowUps().isEmpty() || critique.issues().stream().anyMatch(issue ->
                issue.suggestedAgent() != null || !issue.missingEvidenceTypes().isEmpty()) || !gate.missingEvidenceTypes().isEmpty();
    }

    private com.astrayzjt.faultpilot.common.domain.DiagnosisCritique latestCritique(UUID incidentId) {
        return proposalRepository.findByIncident(incidentId).stream().reduce((first, second) -> second)
                .flatMap(proposal -> critiqueRepository.findByProposal(proposal.proposalId()).stream().reduce((first, second) -> second))
                .orElse(null);
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

    private ModelInteractionException findModelFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelInteractionException modelFailure) {
                return modelFailure;
            }
            current = current.getCause();
        }
        return null;
    }

    private int maxSteps(AgentType type) {
        return type == AgentType.JVM_AGENT ? JVM_AGENT_MAX_STEPS : DEFAULT_AGENT_MAX_STEPS;
    }
}
