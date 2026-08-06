package com.astrayzjt.faultpilot.common.web;

import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ObjectProvider<BuildProperties> buildProperties;
    private final String applicationName;

    public SystemController(
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${spring.application.name:faultpilot-server}") String applicationName) {
        this.buildProperties = buildProperties;
        this.applicationName = applicationName;
    }

    @GetMapping
    public Map<String, String> info() {
        BuildProperties properties = buildProperties.getIfAvailable();
        return Map.of(
                "application", properties == null ? applicationName : properties.getName(),
                "version", properties == null ? "development" : properties.getVersion(),
                "status", "UP");
    }
}
