package com.astrayzjt.faultpilot.incident.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCatalogPropertiesTest {

    @Test
    void bindsBoundedCredentialFreePostgresTarget() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .withPropertyValues(
                        "faultpilot.database.instances.orders.jdbc-url=jdbc:postgresql://postgres:5432/orders",
                        "faultpilot.database.instances.orders.username=faultpilot_diagnostic",
                        "faultpilot.database.instances.orders.password=test-only",
                        "faultpilot.database.instances.orders.query-timeout-seconds=4",
                        "faultpilot.database.instances.orders.max-rows=12")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DatabaseCatalogProperties.PostgresDefinition target = context.getBean(DatabaseCatalogProperties.class)
                            .require("orders");
                    assertThat(target.jdbcUrl()).isEqualTo("jdbc:postgresql://postgres:5432/orders");
                    assertThat(target.queryTimeoutSeconds()).isEqualTo(4);
                    assertThat(target.maxRows()).isEqualTo(12);
                });
    }

    @Test
    void rejectsCredentialsEmbeddedInJdbcUrl() {
        assertThatThrownBy(() -> new DatabaseCatalogProperties.PostgresDefinition(
                "jdbc:postgresql://postgres:5432/orders?password=unsafe", "diagnostic", "test-only", 3, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential-free");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseCatalogProperties.class)
    static class BindingConfiguration {
    }
}
