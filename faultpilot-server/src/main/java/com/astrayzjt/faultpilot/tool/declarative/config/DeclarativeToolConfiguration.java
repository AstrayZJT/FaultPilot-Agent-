package com.astrayzjt.faultpilot.tool.declarative.config;

import com.astrayzjt.faultpilot.tool.declarative.catalog.SkillCatalog;
import com.astrayzjt.faultpilot.tool.declarative.catalog.ToolCatalog;
import com.astrayzjt.faultpilot.tool.declarative.loader.DiagnosticCatalogBundle;
import com.astrayzjt.faultpilot.tool.declarative.loader.DiagnosticDefinitionLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.ResourcePatternResolver;

@Configuration
@EnableConfigurationProperties(DeclarativeToolProperties.class)
public class DeclarativeToolConfiguration {

    @Bean
    DiagnosticCatalogBundle diagnosticCatalogBundle(ResourcePatternResolver resolver,
                                                     DeclarativeToolProperties properties) {
        return new DiagnosticDefinitionLoader(resolver).load(properties);
    }

    @Bean
    ToolCatalog declarativeToolCatalog(DiagnosticCatalogBundle bundle) {
        return bundle.tools();
    }

    @Bean
    SkillCatalog diagnosticSkillCatalog(DiagnosticCatalogBundle bundle) {
        return bundle.skills();
    }
}
