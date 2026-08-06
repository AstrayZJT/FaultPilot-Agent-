package com.astrayzjt.faultpilot.agent.runner;

import com.astrayzjt.faultpilot.agent.protocol.SpecialistAgent;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SpecialistAgentConfiguration {

    @Bean
    SpecialistAgent jvmAgent(SpecialistAgentRunner runner) {
        return new ConfiguredSpecialistAgent(runner, AgentType.JVM_AGENT);
    }

    @Bean
    SpecialistAgent databaseAgent(SpecialistAgentRunner runner) {
        return new ConfiguredSpecialistAgent(runner, AgentType.DATABASE_AGENT);
    }

    @Bean
    SpecialistAgent dependencyAgent(SpecialistAgentRunner runner) {
        return new ConfiguredSpecialistAgent(runner, AgentType.DEPENDENCY_AGENT);
    }
}

