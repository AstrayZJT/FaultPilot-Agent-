package com.astrayzjt.faultpilot.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceCatalogPropertiesTest {

    @Test
    void bindsArthasFieldsAndRetainsTheLegacyCatalogShape() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "faultpilot.catalog.services.order-service.prometheus-labels.job=order",
                        "faultpilot.catalog.services.order-service.actuator-base-url=http://localhost:8081",
                        "faultpilot.catalog.services.order-service.database-ref=lab",
                        "faultpilot.catalog.services.order-service.arthas-base-url=http://127.0.0.1:8563",
                        "faultpilot.catalog.services.order-service.arthas-username=faultpilot",
                        "faultpilot.catalog.services.order-service.arthas-password=test-only",
                        "faultpilot.catalog.services.order-service.code-package-prefixes[0]=com.astrayzjt.faultpilot.lab.order",
                        "faultpilot.catalog.services.order-service.trace-ref=jaeger-primary",
                        "faultpilot.catalog.services.order-service.trace-service-name=orders-api")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ServiceCatalogProperties.ServiceDefinition service =
                            context.getBean(ServiceCatalogProperties.class).require("order-service");
                    assertThat(service.arthasBaseUrl()).isEqualTo("http://127.0.0.1:8563");
                    assertThat(service.arthasUsername()).isEqualTo("faultpilot");
                    assertThat(service.arthasPassword()).isEqualTo("test-only");
                    assertThat(service.codePackagePrefixes()).containsExactly("com.astrayzjt.faultpilot.lab.order");
                    assertThat(service.hasArthasConfiguration()).isTrue();
                    assertThat(service.traceRef()).isEqualTo("jaeger-primary");
                    assertThat(service.traceServiceNameOrDefault("order-service")).isEqualTo("orders-api");
                    assertThat(service.hasTraceConfiguration()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServiceCatalogProperties.class)
    static class BindingConfiguration {
    }
}
