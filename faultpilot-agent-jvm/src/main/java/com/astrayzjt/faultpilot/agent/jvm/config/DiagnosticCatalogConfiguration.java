package com.astrayzjt.faultpilot.agent.jvm.config;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticCatalog;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.JvmDiagnosticDefinitionLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

@Configuration
public class DiagnosticCatalogConfiguration {

    @Bean
    JvmDiagnosticCatalog jvmDiagnosticCatalog(ResourcePatternResolver resolver) {
        return new JvmDiagnosticDefinitionLoader(resolver).load();
    }
}
