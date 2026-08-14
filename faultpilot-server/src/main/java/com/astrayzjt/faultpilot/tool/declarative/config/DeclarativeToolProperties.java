package com.astrayzjt.faultpilot.tool.declarative.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "faultpilot.declarative")
public class DeclarativeToolProperties {

    private boolean enabled = true;
    private List<String> toolLocations = List.of("classpath*:diagnostic/tools/*.yaml");
    private List<String> skillLocations = List.of("classpath*:diagnostic/skills/*/SKILL.md");
    private Set<String> allowedEndpointRefs = new LinkedHashSet<>(
            Set.of("prometheus", "arthas", "postgres", "redis", "trace"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getToolLocations() {
        return toolLocations;
    }

    public void setToolLocations(List<String> toolLocations) {
        this.toolLocations = toolLocations == null ? List.of() : List.copyOf(toolLocations);
    }

    public List<String> getSkillLocations() {
        return skillLocations;
    }

    public void setSkillLocations(List<String> skillLocations) {
        this.skillLocations = skillLocations == null ? List.of() : List.copyOf(skillLocations);
    }

    public Set<String> getAllowedEndpointRefs() {
        return Set.copyOf(allowedEndpointRefs);
    }

    public void setAllowedEndpointRefs(Set<String> allowedEndpointRefs) {
        this.allowedEndpointRefs = allowedEndpointRefs == null
                ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedEndpointRefs);
    }
}
