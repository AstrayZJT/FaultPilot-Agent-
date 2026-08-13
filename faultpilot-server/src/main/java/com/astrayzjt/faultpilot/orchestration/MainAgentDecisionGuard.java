package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.agent.distributed.discovery.CapabilityRegistry;
import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationIdentity;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.DiagnosisStatus;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Local policy boundary around an untrusted Main Agent response. */
@Component
public final class MainAgentDecisionGuard {

    private static final Pattern FORBIDDEN_OBJECTIVE = Pattern.compile(
            "(?is)(https?://|jdbc:|authorization\\s*:|bearer\\s+[a-z0-9._-]+|redis-cli|curl\\s+|wget\\s+|" +
                    "thread\\s+--|\\bselect\\s+.+\\s+from\\b|\\binsert\\s+into\\b|" +
                    "\\bupdate\\s+\\w+\\s+set\\b|\\bdelete\\s+from\\b|\\bdrop\\s+(table|database)\\b|" +
                    "\\balter\\s+table\\b)");

    private final CapabilityRegistry capabilities;

    public MainAgentDecisionGuard(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public MainAgentDecision validate(MainAgentDecision decision, MainAgentContext context) {
        if (decision == null) {
            throw new IllegalArgumentException("Main Agent returned no decision");
        }
        if (context.nextRound() > context.maxRounds() || Instant.now().isAfter(context.deadline())) {
            return MainAgentDecision.inconclusive("Main Agent round budget exhausted");
        }
        Set<UUID> availableEvidence = new HashSet<>();
        for (Evidence item : context.evidence()) {
                if (item == null || item.evidenceId() == null || item.incidentId() == null) {
                    throw new IllegalArgumentException("Evidence has an invalid identity");
                }
                if (!context.incident().incidentId().equals(item.incidentId())) {
                    throw new IllegalArgumentException("Evidence belongs to another Incident");
                }
                availableEvidence.add(item.evidenceId());
        }
        if (!availableEvidence.containsAll(decision.evidenceIds())) {
            throw new IllegalArgumentException("Main Agent referenced evidence outside the current Incident");
        }
        if (decision.action() == MainAgentAction.DELEGATE) {
            AgentType type = decision.agentType();
            if (type == null) {
                throw new IllegalArgumentException("DELEGATE requires an Agent type");
            }
            capabilities.requireAvailable(type);
            if (decision.objective().isBlank() || decision.objective().length() > 2_000) {
                throw new IllegalArgumentException("Delegation objective must contain 1-2000 characters");
            }
            if (FORBIDDEN_OBJECTIVE.matcher(decision.objective()).find()) {
                throw new IllegalArgumentException("Delegation objective contains a forbidden URL, credential, query or command");
            }
            if (decision.draft() != null) {
                throw new IllegalArgumentException("DELEGATE must not include a diagnosis draft");
            }
            if (context.delegations().size() >= context.maxDelegations()) {
                return MainAgentDecision.inconclusive("Main Agent delegation budget exhausted");
            }
            String objectiveHash = DelegationIdentity.objectiveHash(decision.objective());
            boolean duplicate = context.delegations().stream().anyMatch(item -> item.agentType() == type
                    && item.objectiveHash().equals(objectiveHash)
                    && item.status() != DelegationStatus.FAILED
                    && item.status() != DelegationStatus.TIMED_OUT);
            if (duplicate) {
                throw new IllegalArgumentException("Main Agent repeated an already executed delegation");
            }
            return decision;
        }
        if (decision.action() == MainAgentAction.COMPLETE) {
            DiagnosisDraft draft = decision.draft();
            if (draft == null || draft.primaryCause() == CauseCode.UNKNOWN
                    || (draft.status() != DiagnosisStatus.CONFIRMED && draft.status() != DiagnosisStatus.SUPPORTED)
                    || draft.supportingEvidenceIds().isEmpty()) {
                return MainAgentDecision.inconclusive(
                        "Main Agent completion was rejected because it lacked a supported cause and Evidence");
            }
            if (!availableEvidence.containsAll(draft.supportingEvidenceIds())
                    || !availableEvidence.containsAll(draft.counterEvidenceIds())) {
                throw new IllegalArgumentException("Diagnosis draft referenced evidence outside the current Incident");
            }
            return decision;
        }
        return MainAgentDecision.inconclusive(decision.reason().isBlank()
                ? "Main Agent could not establish a supported diagnosis" : decision.reason());
    }
}
