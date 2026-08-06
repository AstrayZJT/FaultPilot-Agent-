package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ServiceCatalogProperties.class)
public class IncidentConfiguration {
}

