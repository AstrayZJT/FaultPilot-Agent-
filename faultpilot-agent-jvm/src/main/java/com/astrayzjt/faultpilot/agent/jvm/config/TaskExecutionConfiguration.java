package com.astrayzjt.faultpilot.agent.jvm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class TaskExecutionConfiguration {

    @Bean
    ThreadPoolTaskExecutor jvmAgentTaskExecutor(JvmAgentProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getTaskThreads());
        executor.setMaxPoolSize(properties.getTaskThreads());
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("jvm-agent-task-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
