package com.astrayzjt.faultpilot.agent.jvm.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JvmAgentStartupValidatorTest {

    @Test
    void acceptsCredentialFreeUrlsTokensAndServiceLabels() {
        JvmAgentProperties properties = validProperties();

        assertThatCode(() -> new JvmAgentStartupValidator(properties).run(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingTokenCredentialsInUrlAndInvalidLabels() {
        JvmAgentProperties missingToken = validProperties();
        missingToken.setA2aToken(" ");
        assertThatThrownBy(() -> new JvmAgentStartupValidator(missingToken).run(null))
                .hasMessageContaining("JVM_AGENT_A2A_TOKEN");

        JvmAgentProperties credentialed = validProperties();
        credentialed.setCentralBaseUrl(URI.create("http://user:password@localhost:8080"));
        assertThatThrownBy(() -> new JvmAgentStartupValidator(credentialed).run(null))
                .hasMessageContaining("credential-free");

        JvmAgentProperties invalidLabel = validProperties();
        JvmAgentProperties.ServiceTarget service = new JvmAgentProperties.ServiceTarget();
        service.setPrometheusLabels(Map.of("bad-label", "value"));
        invalidLabel.setServices(Map.of("order-service", service));
        assertThatThrownBy(() -> new JvmAgentStartupValidator(invalidLabel).run(null))
                .hasMessageContaining("Prometheus label");
    }

    private JvmAgentProperties validProperties() {
        JvmAgentProperties properties = new JvmAgentProperties();
        properties.setA2aToken("a2a-token-123456");
        properties.setCentralToken("central-token-123456");
        properties.setPublicUrl(URI.create("http://localhost:8091/a2a"));
        properties.setCentralBaseUrl(URI.create("http://localhost:8080"));
        JvmAgentProperties.EndpointTarget prometheus = new JvmAgentProperties.EndpointTarget();
        prometheus.setBaseUrl("http://localhost:9090");
        properties.setEndpoints(Map.of("prometheus", prometheus));
        JvmAgentProperties.ServiceTarget service = new JvmAgentProperties.ServiceTarget();
        service.setPrometheusLabels(Map.of("job", "faultpilot-lab-order"));
        properties.setServices(Map.of("order-service", service));
        return properties;
    }
}
