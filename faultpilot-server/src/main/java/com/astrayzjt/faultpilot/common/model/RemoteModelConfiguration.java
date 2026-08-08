package com.astrayzjt.faultpilot.common.model;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RemoteModelConfiguration.ModelProperties.class)
public class RemoteModelConfiguration {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${faultpilot.model.api-key:}')")
    ChatModel remoteChatModel(ModelProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .modelName(properties.modelName())
                .temperature(properties.temperature())
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .maxRetries(0)
                .build();
    }

    @Bean
    ApplicationRunner requireRemoteModel(ModelProperties properties, ObjectProvider<ChatModel> modelProvider) {
        return args -> {
            if (properties.required() && modelProvider.getIfAvailable() == null) {
                throw new RemoteModelUnavailableException(
                        "FAULTPILOT_REQUIRE_REMOTE_MODEL is enabled but QWEN_API_KEY is missing");
            }
        };
    }

    @ConfigurationProperties(prefix = "faultpilot.model")
    public record ModelProperties(
            String baseUrl,
            String modelName,
            String apiKey,
            double temperature,
            long timeoutSeconds,
            boolean required) {

        public ModelProperties {
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 8;
            }
        }
    }
}
