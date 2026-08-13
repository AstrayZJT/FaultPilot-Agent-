package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.DiagnosticObservation;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.EvidenceType;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticCatalog;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticToolExecutor;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.LoadedSkill;
import com.astrayzjt.faultpilot.agent.jvm.evidence.CentralEvidenceClient;
import com.astrayzjt.faultpilot.agent.jvm.evidence.RemoteEvidenceView;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.JvmReasoningModel;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.SkillDecision;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.StepAction;
import com.astrayzjt.faultpilot.agent.jvm.reasoning.StepDecision;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

@Component
public final class LoopJvmInvestigationExecutor implements JvmInvestigationExecutor {

    private final JvmDiagnosticCatalog catalog;
    private final JvmDiagnosticToolExecutor tools;
    private final CentralEvidenceClient evidenceClient;
    private final JvmReasoningModel model;

    public LoopJvmInvestigationExecutor(JvmDiagnosticCatalog catalog, JvmDiagnosticToolExecutor tools,
                                        CentralEvidenceClient evidenceClient, JvmReasoningModel model) {
        this.catalog = catalog;
        this.tools = tools;
        this.evidenceClient = evidenceClient;
        this.model = model;
    }

    @Override
    public TaskOutcome execute(DelegationRequest task, JvmTaskProgress progress, BooleanSupplier canceled,
                               JvmInvestigationProgressRecorder recorder) {
        List<UUID> requestedIds = java.util.stream.Stream.concat(task.availableEvidenceIds().stream(),
                progress.evidenceIds().stream()).distinct().toList();
        List<RemoteEvidenceView> evidence = new ArrayList<>(evidenceClient.query(task, requestedIds));
        LinkedHashSet<UUID> producedIds = new LinkedHashSet<>(progress.evidenceIds());
        LoadedSkill skill;
        try {
            if (progress.selectedSkill().isBlank()) {
                SkillDecision skillDecision = model.chooseSkill(task, List.copyOf(evidence), catalog.skillSummaries());
                skill = catalog.requireSkill(skillDecision.skillName());
                recorder.selectSkill(skill.definition().metadata().name());
            } else {
                skill = catalog.requireSkill(progress.selectedSkill());
            }
        } catch (RuntimeException failure) {
            return insufficient(producedIds, progress.stepsUsed(), "SKILL_SELECTION_FAILED", safeMessage(failure));
        }
        int maxSteps = Math.min(task.limits().maxSteps(), skill.definition().spec().limits().maxSteps());
        Instant skillDeadline = earliest(task.limits().deadline(),
                Instant.now().plusSeconds(skill.definition().spec().limits().timeoutSeconds()));
        Set<String> calledTools = progress.toolCalls().stream().map(JvmToolCallRecord::toolName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int stepsUsed = progress.stepsUsed();
        JvmToolCallRecord pending = progress.toolCalls().stream()
                .filter(call -> call.status() == ToolCallStatus.RESERVED).findFirst().orElse(null);
        while (stepsUsed < maxSteps || pending != null) {
            if (canceled.getAsBoolean()) {
                return new TaskOutcome(TaskStatus.CANCELED, List.copyOf(producedIds), stepsUsed,
                        "A2A_TASK_CANCELED", "Task canceled by orchestrator");
            }
            if (!Instant.now().isBefore(skillDeadline)) {
                return new TaskOutcome(TaskStatus.TIMED_OUT, List.copyOf(producedIds), stepsUsed,
                        "JVM_SKILL_TIMEOUT", "JVM Skill exceeded its deadline");
            }
            if (completed(skill, evidence)) {
                return new TaskOutcome(TaskStatus.COMPLETED, completionEvidenceIds(skill, evidence, producedIds),
                        stepsUsed, null, null);
            }
            if (pending != null) {
                executeReservedTool(task, skillDeadline, pending, evidence, producedIds, recorder);
                pending = null;
                continue;
            }
            StepDecision decision;
            try {
                decision = model.nextStep(task, skill, List.copyOf(evidence), catalog.toolSummaries(skill),
                        Set.copyOf(calledTools), stepsUsed);
                validateDecision(decision, skill, evidence, calledTools);
            } catch (RuntimeException failure) {
                return insufficient(producedIds, stepsUsed, "STEP_DECISION_INVALID", safeMessage(failure));
            }
            if (decision.action() == StepAction.COMPLETE) {
                return completed(skill, evidence)
                        ? new TaskOutcome(TaskStatus.COMPLETED, completionEvidenceIds(skill, evidence, producedIds),
                        stepsUsed, null, null)
                        : insufficient(producedIds, stepsUsed, "COMPLETION_EVIDENCE_MISSING",
                        "JVM Agent requested completion before the Skill evidence gate passed");
            }
            if (decision.action() == StepAction.INSUFFICIENT) {
                return insufficient(producedIds, stepsUsed, "JVM_AGENT_INSUFFICIENT",
                        "JVM Agent concluded that available diagnostic evidence is insufficient");
            }
            String toolName = decision.toolName();
            JvmToolCallRecord call = recorder.reserveTool(toolName);
            calledTools.add(toolName);
            stepsUsed = Math.max(stepsUsed, call.stepIndex() + 1);
            executeReservedTool(task, skillDeadline, call, evidence, producedIds, recorder);
        }
        return completed(skill, evidence)
                ? new TaskOutcome(TaskStatus.COMPLETED, completionEvidenceIds(skill, evidence, producedIds),
                stepsUsed, null, null)
                : insufficient(producedIds, stepsUsed, "JVM_SKILL_STEP_LIMIT", "JVM Skill reached its step limit");
    }

    private void executeReservedTool(DelegationRequest task, Instant deadline, JvmToolCallRecord call,
                                     List<RemoteEvidenceView> evidence, Set<UUID> producedIds,
                                     JvmInvestigationProgressRecorder recorder) {
        DiagnosticObservation observation = tools.execute(catalog.requireTool(call.toolName()), task, deadline);
        UUID evidenceId = null;
        if (observation.evidenceType() != null) {
            RemoteEvidenceView recorded = evidenceClient.record(task, call.toolName(), call.toolCallId(), observation);
            evidenceId = recorded.evidenceId();
            producedIds.add(evidenceId);
            mergeEvidence(evidence, List.of(recorded));
        }
        recorder.completeTool(call, evidenceId);
    }

    private void mergeEvidence(List<RemoteEvidenceView> target, List<RemoteEvidenceView> additions) {
        for (RemoteEvidenceView item : additions) {
            if (target.stream().noneMatch(existing -> existing.evidenceId().equals(item.evidenceId()))) {
                target.add(item);
            }
        }
    }

    private void validateDecision(StepDecision decision, LoadedSkill skill, List<RemoteEvidenceView> evidence,
                                  Set<String> calledTools) {
        Set<UUID> available = evidence.stream().map(RemoteEvidenceView::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(decision.evidenceIds())) {
            throw new IllegalArgumentException("JVM Agent decision references unknown Evidence IDs");
        }
        if (decision.action() == StepAction.CALL_TOOL) {
            if (!skill.definition().spec().allowedTools().contains(decision.toolName())) {
                throw new IllegalArgumentException("JVM Agent selected a Tool outside its Skill whitelist");
            }
            if (calledTools.contains(decision.toolName())) {
                throw new IllegalArgumentException("JVM Agent attempted to call the same Tool twice");
            }
        } else if (!decision.toolName().isBlank()) {
            throw new IllegalArgumentException("Terminal JVM Agent decisions cannot name a Tool");
        }
    }

    private boolean completed(LoadedSkill skill, List<RemoteEvidenceView> evidence) {
        var completion = skill.definition().spec().completion();
        boolean all = completion.allOf().isEmpty() || completion.allOf().stream()
                .allMatch(required -> evidence.stream().anyMatch(item -> item.hasType(required)));
        boolean any = completion.anyOf().isEmpty()
                || completion.anyOf().stream()
                .anyMatch(required -> evidence.stream().anyMatch(item -> item.hasType(required)));
        return all && any;
    }

    private List<UUID> completionEvidenceIds(LoadedSkill skill, List<RemoteEvidenceView> evidence,
                                             Set<UUID> producedIds) {
        Set<EvidenceType> required = new LinkedHashSet<>(skill.definition().spec().completion().allOf());
        required.addAll(skill.definition().spec().completion().anyOf());
        LinkedHashSet<UUID> result = evidence.stream()
                .filter(item -> required.stream().anyMatch(item::hasType))
                .map(RemoteEvidenceView::evidenceId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        result.addAll(producedIds);
        return List.copyOf(result);
    }

    private TaskOutcome insufficient(Set<UUID> evidenceIds, int steps, String code, String message) {
        return new TaskOutcome(TaskStatus.INSUFFICIENT, List.copyOf(evidenceIds), steps, code, message);
    }

    private Instant earliest(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        String value = message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        return value.substring(0, Math.min(500, value.length()));
    }
}
