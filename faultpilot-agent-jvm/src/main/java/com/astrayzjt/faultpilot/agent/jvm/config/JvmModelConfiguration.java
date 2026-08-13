package com.astrayzjt.faultpilot.agent.jvm.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class JvmModelConfiguration {

    @Bean
    ChatModel jvmAgentChatModel(@Value("${faultpilot.model.base-url:}") String baseUrl,
                                @Value("${faultpilot.model.api-key:}") String apiKey,
                                @Value("${faultpilot.model.model-name:qwen3.7-max}") String modelName,
                                @Value("${faultpilot.model.timeout-seconds:90}") long timeoutSeconds) {
        if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("JVM Agent requires MODEL_BASE_URL and QWEN_API_KEY");
        }
        return OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey).modelName(modelName)
                .temperature(0.0).timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds))).maxRetries(0).build();
    }
}
