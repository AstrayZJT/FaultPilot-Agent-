package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.astrayzjt.faultpilot.tool.declarative.catalog.ToolCatalog;
import com.astrayzjt.faultpilot.tool.registry.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DeclarativeHttpProperties.class)
public class DeclarativeExecutionConfiguration {

    @Bean
    DiagnosticToolExecutor diagnosticToolExecutor(DeclarativeHttpProperties properties,
                                                   ToolRegistry toolRegistry,
                                                   ServiceCatalogProperties services,
                                                   ObservabilityProperties observability,
                                                   ObjectMapper objectMapper) {
        if (properties.getMode() == DeclarativeHttpProperties.Mode.LOCAL) {
            return new LocalDiagnosticToolExecutor(toolRegistry);
        }
        EndpointCatalog endpoints = new ConfiguredEndpointCatalog(properties);
        DiagnosticRequestBuilder requestBuilder = new DiagnosticRequestBuilder(services, objectMapper);
        DiagnosticResponseMapper responseMapper = new DiagnosticResponseMapper(objectMapper,
                new DiagnosticThresholdCatalog(observability));
        return new HttpDiagnosticExecutor(endpoints, requestBuilder, new JdkDiagnosticHttpClient(), responseMapper);
    }

    @Bean
    DeclarativeToolInvoker declarativeToolInvoker(ToolCatalog catalog, DiagnosticToolExecutor executor) {
        return new DeclarativeToolInvoker(catalog, executor);
    }
}
