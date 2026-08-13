package com.astrayzjt.faultpilot.agent.jvm.reasoning;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.LoadedSkill;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.SkillSummary;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.ToolSummary;
import com.astrayzjt.faultpilot.agent.jvm.evidence.RemoteEvidenceView;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public final class RemoteJvmReasoningModel implements JvmReasoningModel {

    private final ChatModel model;
    private final ObjectMapper objectMapper;

    public RemoteJvmReasoningModel(ChatModel model, ObjectMapper objectMapper) {
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public SkillDecision chooseSkill(DelegationRequest task, List<RemoteEvidenceView> evidence,
                                     List<SkillSummary> candidates) {
        String system = "You are the restricted FaultPilot JVM specialist Agent. Choose exactly one supplied Skill. " +
                "Do not invent names, facts, URLs, Tools, commands, or Evidence IDs. Return JSON only: " +
                "{\"skillName\":\"...\",\"rationale\":\"...\"}.";
        String user = "Objective=" + task.objective() + "\nIncident=" + json(task.incident())
                + "\nExistingEvidence=" + json(evidence) + "\nCandidateSkills=" + json(candidates);
        return constrained(system, user, SkillDecision.class);
    }

    @Override
    public StepDecision nextStep(DelegationRequest task, LoadedSkill skill, List<RemoteEvidenceView> evidence,
                                 List<ToolSummary> tools, Set<String> calledTools, int step) {
        String system = "You are the restricted FaultPilot JVM specialist Agent executing one selected Skill. " +
                "Choose CALL_TOOL, COMPLETE, or INSUFFICIENT. CALL_TOOL may name only one supplied Tool. " +
                "Do not create arguments, URLs, PromQL, commands, facts, or Evidence IDs. " +
                "Return JSON only: {\"action\":\"CALL_TOOL|COMPLETE|INSUFFICIENT\",\"toolName\":\"\","
                + "\"evidenceIds\":[],\"rationale\":\"...\"}.";
        String user = "Step=" + step + "\nObjective=" + task.objective() + "\nIncident=" + json(task.incident())
                + "\nSkill=" + json(skill.definition()) + "\nSkillInstructions=" + skill.instructions()
                + "\nAllowedTools=" + json(tools) + "\nCalledTools=" + json(calledTools)
                + "\nEvidence=" + json(evidence);
        return constrained(system, user, StepDecision.class);
    }

    private <T> T constrained(String system, String user, Class<T> type) {
        String raw = complete(system, user);
        try {
            return objectMapper.readValue(stripFence(raw), type);
        } catch (RuntimeException | JsonProcessingException first) {
            String repaired = complete("Repair the supplied text into one valid JSON object for schema "
                    + type.getSimpleName() + ". Return JSON only and preserve only supplied values.", raw);
            try {
                return objectMapper.readValue(stripFence(repaired), type);
            } catch (JsonProcessingException second) {
                throw new IllegalStateException("JVM reasoning model returned invalid constrained JSON", second);
            }
        }
    }

    private String complete(String system, String user) {
        String output = model.chat(ChatRequest.builder().messages(List.of(SystemMessage.from(system),
                        UserMessage.from(user))).temperature(0.0).maxOutputTokens(500).build())
                .aiMessage().text();
        if (output == null || output.isBlank()) {
            throw new IllegalStateException("JVM reasoning model returned an empty response");
        }
        return output;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot build JVM reasoning prompt", exception);
        }
    }

    private String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine > 0 && lastFence > firstLine) {
                return trimmed.substring(firstLine + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
