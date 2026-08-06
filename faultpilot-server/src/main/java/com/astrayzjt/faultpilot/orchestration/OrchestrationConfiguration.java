package com.astrayzjt.faultpilot.orchestration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class OrchestrationConfiguration {

    @Bean("orchestratorExecutor")
    Executor orchestratorExecutor() {
        return executor("faultpilot-orchestrator-", 2, 4, 100);
    }

    @Bean("specialistAgentExecutor")
    Executor specialistAgentExecutor() {
        return executor("faultpilot-specialist-", 3, 6, 20);
    }

    private Executor executor(String prefix, int core, int max, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(capacity);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}

