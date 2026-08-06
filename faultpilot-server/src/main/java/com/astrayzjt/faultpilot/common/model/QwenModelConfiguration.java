package com.astrayzjt.faultpilot.common.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(QwenModelConfiguration.QwenModelProperties.class)
public class QwenModelConfiguration {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${QWEN_API_KEY:}')")
    ChatModel qwenChatModel(QwenModelProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.modelName())
                .temperature(properties.temperature())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .build();
    }

    @ConfigurationProperties(prefix = "faultpilot.model")
    public record QwenModelProperties(
            String baseUrl,
            String modelName,
            String apiKey,
            double temperature,
            long timeoutSeconds) {

        public QwenModelProperties {
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 8;
            }
        }
    }
}
