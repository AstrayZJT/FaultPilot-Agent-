package com.astrayzjt.faultpilot.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceCatalogPropertiesTest {

    @Test
    void bindsBoundedJaegerBackendAndBuildsConfiguredAuthorizationOnly() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "faultpilot.trace.jaeger.primary.base-url=https://jaeger.internal:16686",
                        "faultpilot.trace.jaeger.primary.bearer-token=test-only-token",
                        "faultpilot.trace.jaeger.primary.lookback-minutes=10",
                        "faultpilot.trace.jaeger.primary.max-traces=8")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TraceCatalogProperties.JaegerDefinition target = context.getBean(TraceCatalogProperties.class)
                            .requireJaeger("primary");
                    assertThat(target.baseUrl()).isEqualTo("https://jaeger.internal:16686");
                    assertThat(target.authorizationHeader()).isEqualTo("Bearer test-only-token");
                    assertThat(target.lookbackMinutes()).isEqualTo(10);
                    assertThat(target.maxTraces()).isEqualTo(8);
                });
    }

    @Test
    void rejectsQueryParametersInTraceBackendUrl() {
        assertThatThrownBy(() -> new TraceCatalogProperties.JaegerDefinition(
                "https://jaeger.internal:16686?token=unsafe", null, null, null, 15, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-free");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TraceCatalogProperties.class)
    static class BindingConfiguration {
    }
}
