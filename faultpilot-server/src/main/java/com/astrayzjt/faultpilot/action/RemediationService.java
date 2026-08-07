package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.common.domain.IncidentStatus;
import com.astrayzjt.faultpilot.common.domain.PendingAction;
import com.astrayzjt.faultpilot.common.domain.PendingActionStatus;
import com.astrayzjt.faultpilot.common.domain.RiskLevel;
import com.astrayzjt.faultpilot.diagnosis.DiagnosisRepository;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.config.IntegrationProperties;
import com.astrayzjt.faultpilot.incident.config.RemediationProperties;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class RemediationService {

    private final IncidentService incidentService;
    private final DiagnosisRepository diagnosisRepository;
    private final PendingActionRepository repository;
    private final ActionCatalog actionCatalog;
    private final IncidentEventService eventService;
    private final Executor remediationExecutor;
    private final IntegrationProperties integrationProperties;
    private final RemediationProperties remediationProperties;

    public RemediationService(IncidentService incidentService, DiagnosisRepository diagnosisRepository,
                              PendingActionRepository repository, ActionCatalog actionCatalog,
                              IncidentEventService eventService,
                              @Qualifier("remediationExecutor") Executor remediationExecutor,
                              IntegrationProperties integrationProperties, RemediationProperties remediationProperties) {
        this.incidentService = incidentService;
        this.diagnosisRepository = diagnosisRepository;
        this.repository = repository;
        this.actionCatalog = actionCatalog;
        this.eventService = eventService;
        this.remediationExecutor = remediationExecutor;
        this.integrationProperties = integrationProperties;
        this.remediationProperties = remediationProperties;
    }

    public boolean isEnabled() {
        return remediationProperties.isEnabled() && integrationProperties.isLab();
    }

    public boolean canPrepare(DiagnosisDecision decision) {
        return isEnabled() && actionFor(decision).map(actionCatalog::supports).orElse(false);
    }

    public String unavailableReason(DiagnosisDecision decision) {
        if (!isEnabled()) {
            return "Remediation is disabled outside LAB mode";
        }
        return actionFor(decision).isEmpty()
                ? "No pre-registered LAB remediation action exists for this diagnosis"
                : "The configured LAB remediation action is unavailable";
    }

    @Transactional
    public PendingAction prepare(UUID incidentId) {
        if (!isEnabled()) {
            throw new IllegalStateException("Remediation is disabled in the current integration mode");
        }
        Incident incident = incidentService.find(incidentId).orElseThrow();
        DiagnosisDecision decision = diagnosisRepository.find(incidentId)
                .filter(value -> value.status() == DiagnosisStatus.CONFIRMED).orElseThrow();
        ActionCode code = actionFor(decision)
                .orElseThrow(() -> new IllegalStateException("No pre-registered remediation action exists for this diagnosis"));
        actionCatalog.require(code);
        String key = incidentId + ":" + code;
        Map<String, Object> parameters = Map.of("targetService", incident.snapshot().serviceName());
        PendingAction candidate = new PendingAction(UUID.randomUUID(), incidentId, code, RiskLevel.HIGH, parameters,
                hash(parameters), PendingActionStatus.PENDING, key, Instant.now().plus(Duration.ofMinutes(5)),
                null, null, null, null, Map.of(), null, null, 0);
        repository.insert(candidate);
        PendingAction action = repository.findByIdempotencyKey(key).orElseThrow();
        incidentService.updateStatus(incidentId, IncidentStatus.WAITING_ACTION_CONFIRMATION);
        eventService.append(incidentId, "ACTION_PENDING", Map.of("actionId", action.id(), "actionCode", action.actionCode()));
        return action;
    }

    @Transactional
    public PendingAction confirm(UUID actionId, String confirmedBy, String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("X-Request-Id is required for action confirmation");
        }
        PendingAction action = repository.lock(actionId).orElseThrow();
        if (action.status() == PendingActionStatus.CONFIRMED || action.status() == PendingActionStatus.EXECUTING
                || action.status() == PendingActionStatus.SUCCEEDED) {
            return action;
        }
        if (action.status() != PendingActionStatus.PENDING) {
            throw new IllegalStateException("Action cannot be confirmed from status " + action.status());
        }
        if (!Instant.now().isBefore(action.expiresAt())) {
            repository.updateStatus(action.id(), PendingActionStatus.PENDING, PendingActionStatus.EXPIRED, action.version());
            throw new IllegalStateException("Pending action has expired");
        }
        actionCatalog.require(action.actionCode());
        repository.confirm(action.id(), confirmedBy, Instant.now(), action.version());
        eventService.append(action.incidentId(), "ACTION_CONFIRMED", Map.of("actionId", action.id(), "confirmedBy", confirmedBy));
        scheduleExecutionAfterCommit(action.id());
        return repository.find(action.id()).orElse(action);
    }

    @Transactional
    public PendingAction reject(UUID actionId, String rejectedBy) {
        PendingAction action = repository.lock(actionId).orElseThrow();
        if (action.status() == PendingActionStatus.REJECTED) {
            return action;
        }
        if (action.status() != PendingActionStatus.PENDING) {
            throw new IllegalStateException("Action cannot be rejected from status " + action.status());
        }
        repository.reject(action.id(), rejectedBy, Instant.now(), action.version());
        incidentService.updateStatus(action.incidentId(), IncidentStatus.DIAGNOSED);
        eventService.append(action.incidentId(), "ACTION_REJECTED", Map.of("actionId", action.id(), "rejectedBy", rejectedBy));
        return repository.find(action.id()).orElse(action);
    }

    public PendingAction find(UUID actionId) {
        return repository.find(actionId).orElseThrow();
    }

    public List<PendingAction> findByIncident(UUID incidentId) {
        return repository.findByIncident(incidentId);
    }

    public void execute(UUID actionId) {
        PendingAction action = repository.find(actionId).orElseThrow();
        if (action.status() == PendingActionStatus.EXPIRED || action.status() == PendingActionStatus.REJECTED
                || action.status() == PendingActionStatus.SUCCEEDED) {
            return;
        }
        if (action.status() == PendingActionStatus.CONFIRMED) {
            if (!repository.markStarted(action.id(), action.version())) {
                return;
            }
            action = repository.find(actionId).orElseThrow();
        } else if (action.status() != PendingActionStatus.EXECUTING) {
            return;
        }
        var handler = actionCatalog.require(action.actionCode());
        try {
            ActionResult result = executeHandler(handler, action);
            if (!result.success()) {
                repository.markResult(action.id(), PendingActionStatus.FAILED, result.details(), "HANDLER_FAILED", result.summary());
                incidentService.updateStatus(action.incidentId(), IncidentStatus.FAILED);
                eventService.append(action.incidentId(), "ACTION_EXECUTED", Map.of("actionId", action.id(), "status", "FAILED"));
                return;
            }
            boolean recovered = verifyHandler(handler, action);
            repository.markResult(action.id(), recovered ? PendingActionStatus.SUCCEEDED : PendingActionStatus.FAILED,
                    result.details(), recovered ? null : "VERIFICATION_FAILED", result.summary());
            incidentService.updateStatus(action.incidentId(), recovered ? IncidentStatus.RESOLVED : IncidentStatus.FAILED);
            eventService.append(action.incidentId(), "VERIFICATION_COMPLETED", Map.of("actionId", action.id(), "recovered", recovered));
        } catch (RuntimeException exception) {
            repository.markResult(action.id(), PendingActionStatus.FAILED, Map.of(), exception.getClass().getSimpleName(), safeMessage(exception));
            incidentService.updateStatus(action.incidentId(), IncidentStatus.FAILED);
        }
    }

    public void recoverPending() {
        repository.expirePending(Instant.now());
        repository.findByStatuses(java.util.List.of(PendingActionStatus.CONFIRMED, PendingActionStatus.EXECUTING))
                .forEach(action -> remediationExecutor.execute(() -> executeSafely(action.id())));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        recoverPending();
    }

    private java.util.Optional<ActionCode> actionFor(DiagnosisDecision decision) {
        return switch (decision.primaryCause()) {
            case JVM_CPU_HOTSPOT -> java.util.Optional.of(ActionCode.STOP_CPU_FAULT);
            case JVM_THREAD_POOL_EXHAUSTED -> java.util.Optional.of(ActionCode.RELEASE_BLOCKED_TASKS);
            case DB_SLOW_QUERY -> java.util.Optional.of(ActionCode.RESTORE_INDEXED_QUERY);
            case DB_POOL_EXHAUSTED -> java.util.Optional.of(ActionCode.RELEASE_HELD_CONNECTIONS);
            case DEPENDENCY_TIMEOUT -> java.util.Optional.of(ActionCode.RESTORE_DEPENDENCY_LATENCY);
            case REDIS_SERVER_LATENCY, REDIS_CLIENT_POOL_EXHAUSTED, UNKNOWN -> java.util.Optional.empty();
        };
    }

    private void executeSafely(UUID actionId) {
        try {
            execute(actionId);
        } catch (Throwable throwable) {
            repository.markResult(actionId, PendingActionStatus.FAILED, Map.of(),
                    throwable.getClass().getSimpleName(), safeMessage(throwable));
        }
    }

    private void scheduleExecutionAfterCommit(UUID actionId) {
        UUID incidentId = repository.find(actionId).map(PendingAction::incidentId).orElseThrow();
        Runnable task = () -> {
            try {
                remediationExecutor.execute(() -> executeSafely(actionId));
            } catch (RuntimeException exception) {
                eventService.append(incidentId, "ACTION_EXECUTOR_REJECTED", Map.of("actionId", actionId));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    @SuppressWarnings("unchecked")
    private ActionResult executeHandler(RemediationAction<?> handler, PendingAction action) {
        return ((RemediationAction<Map<String, Object>>) handler).execute(action.parameters(),
                new ActionExecutionContext(action.incidentId(), action.id(), action.actionCode(),
                        String.valueOf(action.parameters().get("targetService")), Instant.now().plusSeconds(20)));
    }

    @SuppressWarnings("unchecked")
    private boolean verifyHandler(RemediationAction<?> handler, PendingAction action) {
        return ((RemediationAction<Map<String, Object>>) handler).verify(action.parameters(),
                new ActionExecutionContext(action.incidentId(), action.id(), action.actionCode(),
                        String.valueOf(action.parameters().get("targetService")), Instant.now().plusSeconds(20)));
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash action arguments", exception);
        }
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
    }
}
