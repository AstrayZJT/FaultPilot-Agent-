package com.astrayzjt.faultpilot.common.model;

import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.incident.event.IncidentEventService;
import com.astrayzjt.faultpilot.orchestration.persistence.TraceRepository;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RemoteModelClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteModelClient.class);

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final TraceRepository traceRepository;
    private final IncidentEventService eventService;

    public RemoteModelClient(ObjectProvider<ChatModel> chatModelProvider, TraceRepository traceRepository,
                             IncidentEventService eventService) {
        this.chatModelProvider = chatModelProvider;
        this.traceRepository = traceRepository;
        this.eventService = eventService;
    }

    public String complete(UUID incidentId, UUID taskId, ModelRole role, String promptVersion,
                           String systemPrompt, String userPrompt, int maxOutputTokens) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) {
            traceRepository.model(incidentId, taskId, "unconfigured", role.name().toLowerCase() + "-" + promptVersion,
                    null, null, Instant.now(), "FAILED");
            recordFailure(incidentId, role, promptVersion, "MODEL_NOT_CONFIGURED");
            throw new RemoteModelUnavailableException("Remote ChatModel is not configured; set QWEN_API_KEY");
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            Instant startedAt = Instant.now();
            try {
                List<ChatMessage> messages = List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt));
                String output = model.chat(ChatRequest.builder().messages(messages).temperature(0.0)
                                .maxOutputTokens(maxOutputTokens).build())
                        .aiMessage().text();
                if (output == null || output.isBlank()) {
                    throw new IllegalStateException("Remote model returned an empty response");
                }
                traceRepository.model(incidentId, taskId, model.getClass().getSimpleName(),
                        role.name().toLowerCase() + "-" + promptVersion, null, null, startedAt, "SUCCEEDED");
                return output;
            } catch (RuntimeException exception) {
                traceRepository.model(incidentId, taskId, model.getClass().getSimpleName(),
                        role.name().toLowerCase() + "-" + promptVersion, null, null, startedAt, "FAILED");
                log.warn("Remote model call failed: role={}, promptVersion={}, attempt={}, failure={}",
                        role, promptVersion, attempt, rootCauseType(exception));
                if (attempt == 2) {
                    recordFailure(incidentId, role, promptVersion, failureCode(exception));
                    throw new RemoteModelUnavailableException("Remote model call failed for " + role, exception);
                }
            }
        }
        throw new RemoteModelUnavailableException("Remote model call failed for " + role);
    }

    private void recordFailure(UUID incidentId, ModelRole role, String promptVersion, String code) {
        if (incidentId != null) {
            eventService.append(incidentId, "MODEL_CALL_FAILED", Map.of(
                    "role", role.name(), "promptVersion", promptVersion, "code", code));
        }
    }

    private static String failureCode(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return "REMOTE_TIMEOUT";
            }
            current = current.getCause();
        }
        return "REMOTE_CALL_FAILED";
    }

    private static String rootCauseType(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
