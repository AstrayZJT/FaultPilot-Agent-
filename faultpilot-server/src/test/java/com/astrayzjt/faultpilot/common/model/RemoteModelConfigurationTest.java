package com.astrayzjt.faultpilot.common.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteModelConfigurationTest {

    @Test
    void defaultsInvalidTimeoutToNinetySeconds() {
        var properties = new RemoteModelConfiguration.ModelProperties(
                "http://localhost:1/v1", "glm-5", "test-key", 0, 0, true);

        assertThat(properties.timeoutSeconds()).isEqualTo(90);
    }

    @Test
    void delegatesRetriesToTheAuditedRemoteModelClient() {
        var properties = new RemoteModelConfiguration.ModelProperties(
                "http://localhost:1/v1", "glm-5", "test-key", 0, 45, true);

        OpenAiChatModel model = (OpenAiChatModel) new RemoteModelConfiguration().remoteChatModel(properties);

        assertThat(ReflectionTestUtils.getField(model, "maxRetries")).isEqualTo(0);
    }
}
