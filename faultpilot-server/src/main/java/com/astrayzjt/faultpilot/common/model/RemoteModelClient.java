package com.astrayzjt.faultpilot.common.model;

import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RemoteModelClient {

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final TraceRepository traceRepository;

    public RemoteModelClient(ObjectProvider<ChatModel> chatModelProvider, TraceRepository traceRepository) {
        this.chatModelProvider = chatModelProvider;
        this.traceRepository = traceRepository;
    }

    public String complete(UUID incidentId, UUID taskId, ModelRole role, String promptVersion,
                           String systemPrompt, String userPrompt, int maxOutputTokens) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            throw new RemoteModelUnavailableException("Remote Qwen ChatModel is not configured; set QWEN_API_KEY");
        }
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            Instant startedAt = Instant.now();
            try {
                List<ChatMessage> messages = List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt));
                String output = model.chat(ChatRequest.builder().messages(messages).temperature(0.0)
                                .maxOutputTokens(maxOutputTokens).build())
                        .aiMessage().text();
                if (output == null || output.isBlank()) {
                    throw new IllegalStateException("Remote Qwen returned an empty response");
                }
                traceRepository.model(incidentId, taskId, model.getClass().getSimpleName(),
                        role.name().toLowerCase() + "-" + promptVersion, null, null, startedAt, "SUCCEEDED");
                return output;
            } catch (RuntimeException exception) {
                last = exception;
                traceRepository.model(incidentId, taskId, model.getClass().getSimpleName(),
                        role.name().toLowerCase() + "-" + promptVersion, null, null, startedAt, "FAILED");
                if (attempt == 2) {
                    throw exception;
                }
            }
        }
        throw last == null ? new IllegalStateException("Remote Qwen call failed") : last;
    }
}
