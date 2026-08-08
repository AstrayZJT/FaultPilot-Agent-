package com.astrayzjt.faultpilot.common.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class QwenModelConfigurationTest {

    @Test
    void delegatesRetriesToTheAuditedRemoteModelClient() {
        var properties = new QwenModelConfiguration.QwenModelProperties(
                "http://localhost:1/v1", "qwen-test", "test-key", 0, 45, true);

        OpenAiChatModel model = (OpenAiChatModel) new QwenModelConfiguration().qwenChatModel(properties);

        assertThat(ReflectionTestUtils.getField(model, "maxRetries")).isEqualTo(0);
    }
}
