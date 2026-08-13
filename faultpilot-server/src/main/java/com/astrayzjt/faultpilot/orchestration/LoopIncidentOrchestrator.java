package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.action.RemediationService;
import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.BaselineStatus;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRun;
import com.astrayzjt.faultpilot.agent.distributed.domain.InvestigationRunStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.CapabilitySnapshotRepository;
import com.astrayzjt.faultpilot.agent.distributed.persistence.InvestigationRunRepository;
import com.astrayzjt.faultpilot.agent.distributed.service.DelegationCoordinator;
import com.astrayzjt.faultpilot.agent.distributed.service.InvestigationRunService;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.common.model.ModelInteractionException;
import com.astrayzjt.faultpilot.common.model.ModelOutputInvalidException;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisRepository;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.incident.persistence.IncidentRepository;
import com.astrayzjt.faultpilot.triage.BaselineCollector;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Service
@ConditionalOnProperty(prefix = "faultpilot.orchestration", name = "mode", havingValue = "LOOP", matchIfMissing = true)
public final class LoopIncidentOrchestrator implements IncidentWorkflow {

    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final InvestigationRunService runService;
    private final InvestigationRunRepository runRepository;
    private final CapabilitySnapshotRepository snapshotRepository;
    private final CapabilityRegistry capabilityRegistry;
    private final AgentDelegationRepository delegationRepository;
    private final EvidenceService evidenceService;
    private final BaselineCollector baselineCollector;
    private final MainAgent mainAgent;
    private final MainAgentDecisionGuard decisionGuard;
    private final DelegationCoordinator delegationCoordinator;
    private final SummaryAgent summaryAgent;
    private final DiagnosisRepository diagnosisRepository;
    private final IncidentEventService eventService;
    private final RemediationService remediationService;
    private final OrchestrationProperties properties;
    private final ObjectMapper objectMapper;
    private final Executor orchestratorExecutor;
    private final Executor specialistExecutor;
    private final CompiledGraph<LoopIncidentGraphState> graph;

    public LoopIncidentOrchestrator(
            IncidentService incidentService,
            IncidentRepository incidentRepository,
            InvestigationRunService runService,
            InvestigationRunRepository runRepository,
            CapabilitySnapshotRepository snapshotRepository,
            CapabilityRegistry capabilityRegistry,
            AgentDelegationRepository delegationRepository,
            EvidenceService evidenceService,
            BaselineCollector baselineCollector,
            MainAgent mainAgent,
            MainAgentDecisionGuard decisionGuard,
            DelegationCoordinator delegationCoordinator,
            SummaryAgent summaryAgent,
            DiagnosisRepository diagnosisRepository,
            IncidentEventService eventService,
            RemediationService remediationService,
            OrchestrationProperties properties,
            ObjectMapper objectMapper,
            DataSource dataSource,
            @Qualifier("orchestratorExecutor") Executor orchestratorExecutor,
            @Qualifier("specialistAgentExecutor") Executor specialistExecutor) throws Exception {
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.runService = runService;
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.capabilityRegistry = capabilityRegistry;
        this.delegationRepository = delegationRepository;
        this.evidenceService = evidenceService;
        this.baselineCollector = baselineCollector;
        this.mainAgent = mainAgent;
        this.decisionGuard = decisionGuard;
        this.delegationCoordinator = delegationCoordinator;
        this.summaryAgent = summaryAgent;
        this.diagnosisRepository = diagnosisRepository;
        this.eventService = eventService;
        this.remediationService = remediationService;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.orchestratorExecutor = orchestratorExecutor;
        this.specialistExecutor = specialistExecutor;
        this.graph = buildGraph(dataSource);
    }

    @Override
    public void start(UUID incidentId) {
        try {
            orchestratorExecutor.execute(() -> runGraph(incidentId));
        } catch (RejectedExecutionException exception) {
            incidentService.updateStatus(incidentId, IncidentStatus.FAILED);
            eventService.append(incidentId, "ORCHESTRATOR_REJECTED",
                    Map.of("reason", "executor capacity exceeded", "mode", "LOOP"));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingIncidents() {
        Set<IncidentStatus> recoverable = Set.of(IncidentStatus.ACCEPTED, IncidentStatus.INVESTIGATING,
                IncidentStatus.REMEDIATING, IncidentStatus.VERIFYING);
        incidentRepository.findIdsByStatus(recoverable).forEach(this::start);
    }

    private void runGraph(UUID incidentId) {
        InvestigationRun run = runService.getOrCreate(incidentId);
        RunnableConfig config = RunnableConfig.builder()
                .threadId("incident:" + incidentId + ":run:" + run.runId()).build();
        try {
            if (graph.lastStateOf(config).isPresent()) {
                graph.invoke(GraphInput.resume(), config);
            } else {
                graph.invoke(Map.of(
                        "incidentId", incidentId.toString(),
                        "runId", run.runId().toString(),
                        "round", 0,
                        "action", "",
                        "delegationsJson", "[]",
                        "evidenceIds", List.of(),
                        "diagnosisDraftJson", "",
                        "outcome", "RUNNING"), config);
            }
        } catch (RuntimeException exception) {
            handleFailure(incidentId, run.runId(), exception);
        }
    }

    private CompiledGraph<LoopIncidentGraphState> buildGraph(DataSource dataSource) throws Exception {
        ObjectStreamStateSerializer<LoopIncidentGraphState> serializer =
                new ObjectStreamStateSerializer<>(LoopIncidentGraphState::new);
        PostgresSaver saver = PostgresSaver.builder().datasource(dataSource).stateSerializer(serializer)
                .createTables(true).dropTablesFirst(false).build();
        Map<String, org.bsc.langgraph4j.state.Channel<?>> channels = new LinkedHashMap<>();
        channels.put("incidentId", Channels.base(() -> ""));
        channels.put("runId", Channels.base(() -> ""));
        channels.put("round", Channels.base(() -> 0));
        channels.put("action", Channels.base(() -> ""));
        channels.put("delegationsJson", Channels.base(() -> "[]"));
        channels.put("evidenceIds", Channels.base((java.util.function.Supplier<List<String>>) List::of));
        channels.put("diagnosisDraftJson", Channels.base(() -> ""));
        channels.put("outcome", Channels.base(() -> "RUNNING"));

        StateGraph<LoopIncidentGraphState> stateGraph = new StateGraph<>(channels, serializer);
        stateGraph.addNode("main_agent", node_async(this::mainAgentNode));
        stateGraph.addNode("specialist_agent", node_async(this::specialistAgentNode));
        stateGraph.addNode("summary_agent", node_async(this::summaryAgentNode));
        stateGraph.addEdge(START, "main_agent");
        stateGraph.addConditionalEdges("main_agent", edge_async(this::routeMainDecision), Map.of(
                "delegate", "specialist_agent",
                "complete", "summary_agent",
                "inconclusive", END));
        stateGraph.addEdge("specialist_agent", "main_agent");
        stateGraph.addEdge("summary_agent", END);
        return stateGraph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false)
                .recursionLimit(30).graphId("faultpilot-main-agent-loop-v1").build());
    }

    private Map<String, Object> mainAgentNode(LoopIncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        InvestigationRun run = prepareRun(state.runId(), incident);
        int round = state.round() + 1;
        List<Evidence> evidence = evidenceService.findActiveByRun(run.runId());
        var snapshot = snapshotRepository.find(run.capabilitySnapshotId()).orElseThrow();
        MainAgentContext context = new MainAgentContext(incident.snapshot(), run, snapshot, evidence,
                delegationRepository.findByRun(run.runId()), round, properties.getMaxRounds(),
                properties.getMaxDelegations(), deadline(run));
        MainAgentDecision proposed = round > properties.getMaxRounds() || Instant.now().isAfter(deadline(run))
                ? MainAgentDecision.inconclusive("Main Agent investigation budget exhausted")
                : mainAgent.decide(context);
        MainAgentDecision decision = decisionGuard.validate(proposed, context);
        eventService.append(state.incidentId(), "MAIN_AGENT_DECIDED", Map.of(
                "runId", run.runId(),
                "round", round,
                "action", decision.action(),
                "delegations", decision.delegations(),
                "evidenceIds", decision.evidenceIds(),
                "reason", decision.reason()));
        if (decision.action() == MainAgentAction.INCONCLUSIVE) {
            finishInconclusive(incident, run, decision.reason(), decision.evidenceIds());
        }
        return decisionState(decision, round);
    }

    private Map<String, Object> specialistAgentNode(LoopIncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        InvestigationRun run = runRepository.find(state.runId()).orElseThrow();
        List<SpecialistDelegation> requests = delegations(state.delegationsJson());
        requests.forEach(request -> eventService.append(state.incidentId(), "DELEGATION_SUBMITTED", Map.of(
                "runId", run.runId(), "round", state.round(), "agentType", request.agentType(),
                "objective", request.objective())));
        List<CompletableFuture<com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation>> futures = requests
                .stream().map(request -> CompletableFuture.supplyAsync(() -> delegationCoordinator.execute(run,
                        state.round(), request.agentType(), request.objective(), incident.snapshot(), deadline(run)),
                        specialistExecutor)).toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        futures.stream().map(CompletableFuture::join).forEach(delegation ->
                eventService.append(state.incidentId(), "DELEGATION_COMPLETED", Map.of(
                        "runId", run.runId(),
                        "delegationId", delegation.delegationId(),
                        "agentId", delegation.agentId(),
                        "status", delegation.status(),
                        "evidenceIds", delegation.artifact() == null ? List.of()
                                : delegation.artifact().evidenceIds())));
        return Map.of("outcome", "REFLECTING");
    }

    private Map<String, Object> summaryAgentNode(LoopIncidentGraphState state) {
        Incident incident = incidentService.find(state.incidentId()).orElseThrow();
        InvestigationRun run = runRepository.find(state.runId()).orElseThrow();
        DiagnosisDraft draft = draft(state.diagnosisDraftJson());
        List<Evidence> evidence = evidenceService.findActiveByRun(run.runId());
        SummaryAgent.SummaryResult summary = summaryAgent.summarize(incident.snapshot(), draft, evidence);
        DiagnosisDecision decision = draft.toDecision(summary.summary());
        diagnosisRepository.save(state.incidentId(), decision);
        completeRun(run, InvestigationRunStatus.COMPLETED, null);
        incidentService.updateStatus(state.incidentId(), IncidentStatus.DIAGNOSED);
        eventService.append(state.incidentId(), "SUMMARY_COMPLETED", Map.of(
                "runId", run.runId(), "fallbackUsed", summary.fallbackUsed(),
                "status", decision.status(), "primaryCause", decision.primaryCause(),
                "evidenceIds", decision.supportingEvidenceIds()));
        prepareRemediation(incident, decision);
        eventService.append(state.incidentId(), "DIAGNOSIS_COMPLETED", decision);
        return Map.of("outcome", "DIAGNOSED");
    }

    private InvestigationRun prepareRun(UUID runId, Incident incident) {
        InvestigationRun run = runRepository.find(runId).orElseThrow();
        if (run.status() == InvestigationRunStatus.PENDING) {
            transitionRun(run, InvestigationRunStatus.RUNNING, null);
            run = runRepository.find(runId).orElseThrow();
            incidentService.updateStatus(incident.incidentId(), IncidentStatus.INVESTIGATING);
            eventService.append(incident.incidentId(), "INVESTIGATION_STARTED", Map.of(
                    "serviceName", incident.snapshot().serviceName(), "runId", run.runId(), "mode", "LOOP"));
            eventService.append(incident.incidentId(), "CAPABILITY_SNAPSHOT_BOUND", Map.of(
                    "runId", run.runId(), "snapshotId", run.capabilitySnapshotId(),
                    "agents", capabilityRegistry.currentSnapshot().agents().stream()
                    .map(agent -> Map.of("agentId", agent.agentId(), "agentType", agent.agentType(),
                            "capabilityVersion", agent.capabilityVersion(), "status", agent.status())).toList()));
        }
        if (run.baselineStatus() == BaselineStatus.PENDING || run.baselineStatus() == BaselineStatus.RUNNING) {
            if (run.baselineStatus() == BaselineStatus.PENDING) {
                transitionBaseline(run, BaselineStatus.RUNNING);
                run = runRepository.find(runId).orElseThrow();
            }
            try {
                List<Evidence> collected = baselineCollector.collect(incident, run.runId(),
                        snapshotRepository.find(run.capabilitySnapshotId()).orElseThrow());
                transitionBaseline(run, BaselineStatus.COMPLETED);
                eventService.append(incident.incidentId(), "BASELINE_COLLECTED", Map.of(
                        "runId", run.runId(), "count", collected.size(),
                        "evidenceIds", collected.stream().map(Evidence::evidenceId).toList()));
            } catch (RuntimeException exception) {
                transitionBaseline(run, BaselineStatus.FAILED);
                throw exception;
            }
            run = runRepository.find(runId).orElseThrow();
        }
        return run;
    }

    private Map<String, Object> decisionState(MainAgentDecision decision, int round) {
        return Map.of(
                "round", round,
                "action", decision.action().name(),
                "delegationsJson", json(decision.delegations()),
                "evidenceIds", decision.evidenceIds().stream().map(UUID::toString).toList(),
                "diagnosisDraftJson", decision.draft() == null ? "" : json(decision.draft()),
                "outcome", switch (decision.action()) {
                    case DELEGATE -> "DELEGATING";
                    case COMPLETE -> "SUMMARIZING";
                    case INCONCLUSIVE -> "INCONCLUSIVE";
                });
    }

    private String routeMainDecision(LoopIncidentGraphState state) {
        return route(MainAgentAction.valueOf(state.action()));
    }

    static String route(MainAgentAction action) {
        return switch (action) {
            case DELEGATE -> "delegate";
            case COMPLETE -> "complete";
            case INCONCLUSIVE -> "inconclusive";
        };
    }

    private void finishInconclusive(Incident incident, InvestigationRun run, String reason, List<UUID> evidenceIds) {
        String summary = reason == null || reason.isBlank()
                ? "The investigation ended without enough active Evidence" : reason;
        DiagnosisDecision decision = new DiagnosisDecision(DiagnosisStatus.INCONCLUSIVE,
                com.astrayzjt.faultpilot.common.domain.CauseCode.UNKNOWN, List.of(), List.of(), List.of(),
                List.of(), summary);
        diagnosisRepository.save(incident.incidentId(), decision);
        completeRun(run, InvestigationRunStatus.INCONCLUSIVE, "EVIDENCE_INSUFFICIENT");
        incidentService.updateStatus(incident.incidentId(), IncidentStatus.INCONCLUSIVE);
        eventService.append(incident.incidentId(), "DIAGNOSIS_INCONCLUSIVE", Map.of(
                "runId", run.runId(), "reason", summary, "evidenceIds", evidenceIds));
    }

    private void prepareRemediation(Incident incident, DiagnosisDecision decision) {
        if (decision.status() == DiagnosisStatus.CONFIRMED && incident.snapshot().allowRemediation()
                && remediationService.canPrepare(decision)) {
            remediationService.prepare(incident.incidentId());
        } else if (incident.snapshot().allowRemediation()) {
            eventService.append(incident.incidentId(), "ACTION_SKIPPED", Map.of(
                    "reason", decision.status() == DiagnosisStatus.SUPPORTED
                            ? "Diagnosis is supported but not confirmed"
                            : remediationService.unavailableReason(decision)));
        }
    }

    private void handleFailure(UUID incidentId, UUID runId, RuntimeException exception) {
        ModelInteractionException modelFailure = findModelFailure(exception);
        InvestigationRun run = runRepository.find(runId).orElse(null);
        if (modelFailure != null) {
            if (run != null && !run.status().terminal()) {
                completeRun(run, InvestigationRunStatus.INCONCLUSIVE, "MODEL_FAILURE");
            }
            incidentService.updateStatus(incidentId, IncidentStatus.INCONCLUSIVE);
            if (modelFailure instanceof ModelOutputInvalidException invalidOutput) {
                eventService.append(incidentId, "MODEL_OUTPUT_INVALID",
                        Map.of("role", invalidOutput.role().name()));
            }
            eventService.append(incidentId, "DIAGNOSIS_INCONCLUSIVE", Map.of(
                    "runId", runId,
                    "reason", "A required remote model role was unavailable or returned an invalid constrained response",
                    "modelFailure", modelFailure.getClass().getSimpleName()));
            return;
        }
        if (run != null && !run.status().terminal()) {
            completeRun(run, InvestigationRunStatus.FAILED, exception.getClass().getSimpleName());
        }
        incidentService.updateStatus(incidentId, IncidentStatus.FAILED);
        eventService.append(incidentId, "ORCHESTRATION_FAILED", Map.of(
                "runId", runId, "error", exception.getClass().getSimpleName(),
                "message", safeMessage(exception)));
    }

    private void transitionRun(InvestigationRun run, InvestigationRunStatus target, String reason) {
        if (!runRepository.transition(run.runId(), run.status(), target, run.version(), reason,
                target.terminal() ? Instant.now() : null)) {
            throw new IllegalStateException("Investigation Run state changed concurrently");
        }
    }

    private void completeRun(InvestigationRun run, InvestigationRunStatus target, String reason) {
        InvestigationRun current = runRepository.find(run.runId()).orElseThrow();
        if (!current.status().terminal()) {
            transitionRun(current, target, reason);
        }
    }

    private void transitionBaseline(InvestigationRun run, BaselineStatus target) {
        if (!runRepository.transitionBaseline(run.runId(), run.baselineStatus(), target, run.version())) {
            throw new IllegalStateException("Investigation Baseline state changed concurrently");
        }
    }

    private Instant deadline(InvestigationRun run) {
        return run.startedAt().plusSeconds(properties.getRunTimeoutSeconds());
    }

    private DiagnosisDraft draft(String json) {
        try {
            return objectMapper.readValue(json, DiagnosisDraft.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Checkpoint contains an invalid DiagnosisDraft", exception);
        }
    }

    private List<SpecialistDelegation> delegations(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, SpecialistDelegation.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Checkpoint contains invalid Specialist delegations", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Loop graph state", exception);
        }
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

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        String value = message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
