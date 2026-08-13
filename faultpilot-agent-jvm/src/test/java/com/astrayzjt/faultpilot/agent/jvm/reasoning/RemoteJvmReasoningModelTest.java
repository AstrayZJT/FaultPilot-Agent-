package com.astrayzjt.faultpilot.agent.jvm.reasoning;

import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteJvmReasoningModelTest {

    @Test
    void parsesValidConstrainedJsonWithoutRepair() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(response(
                "{\"skillName\":\"jvm-cpu-hotspot\",\"rationale\":\"CPU evidence\"}"));
        RemoteJvmReasoningModel model = model(chat);

        SkillDecision result = model.chooseSkill(task(), List.of(), List.of());

        assertThat(result.skillName()).isEqualTo("jvm-cpu-hotspot");
        verify(chat).chat(any(ChatRequest.class));
    }

    @Test
    void repairsOneInvalidResponseThenParsesIt() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(response("not-json"), response(
                "```json\n{\"skillName\":\"jvm-thread-pool-exhausted\",\"rationale\":\"pool saturated\"}\n```"));
        RemoteJvmReasoningModel model = model(chat);

        SkillDecision result = model.chooseSkill(task(), List.of(), List.of());

        assertThat(result.skillName()).isEqualTo("jvm-thread-pool-exhausted");
        verify(chat, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void failsAfterTheSingleRepairAttemptAlsoReturnsInvalidJson() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(response("not-json"), response("still-not-json"));
        RemoteJvmReasoningModel model = model(chat);

        assertThatThrownBy(() -> model.chooseSkill(task(), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid constrained JSON");
        verify(chat, times(2)).chat(any(ChatRequest.class));
    }

    private RemoteJvmReasoningModel model(ChatModel chat) {
        return new RemoteJvmReasoningModel(chat, JsonMapper.builder().findAndAddModules().build());
    }

    private ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private DelegationRequest task() {
        Instant now = Instant.now();
        return new DelegationRequest(DelegationRequest.SCHEMA_VERSION, UUID.randomUUID(), "key", "1.0.0",
                new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(), "order-service",
                        "CPU high", new TimeRange(now.minusSeconds(60), now)), "Find CPU hotspot", List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(30)));
    }
}
