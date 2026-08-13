package com.astrayzjt.faultpilot.orchestration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationPropertiesTest {

    @Test
    void bindsLoopBudgetsAndRejectsUnsafeBounds() {
        new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("faultpilot.orchestration.mode=LOOP",
                        "faultpilot.orchestration.max-rounds=6",
                        "faultpilot.orchestration.max-delegations=12",
                        "faultpilot.orchestration.run-timeout-seconds=300")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OrchestrationProperties properties = context.getBean(OrchestrationProperties.class);
                    assertThat(properties.getMode()).isEqualTo(OrchestrationProperties.Mode.LOOP);
                    assertThat(properties.getMaxRounds()).isEqualTo(6);
                    assertThat(properties.getMaxDelegations()).isEqualTo(12);
                    assertThat(properties.getRunTimeoutSeconds()).isEqualTo(300);
                });

        new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("faultpilot.orchestration.max-rounds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestrationProperties.class)
    static class TestConfiguration {
    }
}
