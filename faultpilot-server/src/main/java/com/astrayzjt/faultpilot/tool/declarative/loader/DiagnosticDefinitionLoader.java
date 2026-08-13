package com.astrayzjt.faultpilot.tool.declarative.loader;

import com.astrayzjt.faultpilot.tool.declarative.catalog.YamlSkillCatalog;
import com.astrayzjt.faultpilot.tool.declarative.catalog.YamlToolCatalog;
import com.astrayzjt.faultpilot.tool.declarative.config.DeclarativeToolProperties;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedSkill;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedTool;
import com.astrayzjt.faultpilot.tool.declarative.model.SkillDefinition;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.declarative.validation.DiagnosticDefinitionException;
import com.astrayzjt.faultpilot.tool.declarative.validation.DiagnosticDefinitionValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiagnosticDefinitionLoader {

    private final ResourcePatternResolver resources;
    private final ObjectMapper yamlMapper;
    private final DiagnosticDefinitionValidator validator;

    public DiagnosticDefinitionLoader(ResourcePatternResolver resources) {
        this(resources, strictYamlMapper(), new DiagnosticDefinitionValidator());
    }

    DiagnosticDefinitionLoader(ResourcePatternResolver resources, ObjectMapper yamlMapper,
                               DiagnosticDefinitionValidator validator) {
        this.resources = resources;
        this.yamlMapper = yamlMapper;
        this.validator = validator;
    }

    public DiagnosticCatalogBundle load(DeclarativeToolProperties properties) {
        if (!properties.isEnabled()) {
            return new DiagnosticCatalogBundle(new YamlToolCatalog(List.of()), new YamlSkillCatalog(List.of()));
        }
        List<LoadedTool> tools = loadTools(properties.getToolLocations());
        List<LoadedSkill> skills = loadSkills(properties.getSkillLocations());
        validator.validate(tools, skills, properties.getAllowedEndpointRefs());
        return new DiagnosticCatalogBundle(new YamlToolCatalog(tools), new YamlSkillCatalog(skills));
    }

    List<LoadedTool> loadTools(List<String> locations) {
        List<LoadedTool> loaded = new ArrayList<>();
        for (Resource resource : resolve(locations, "tool")) {
            loaded.add(new LoadedTool(readYaml(resource, ToolDefinition.class), description(resource)));
        }
        return List.copyOf(loaded);
    }

    List<LoadedSkill> loadSkills(List<String> locations) {
        List<LoadedSkill> loaded = new ArrayList<>();
        for (Resource resource : resolve(locations, "skill")) {
            SkillDefinition definition = readYaml(resource, SkillDefinition.class);
            Resource instructions = relative(resource, "SKILL.md");
            loaded.add(new LoadedSkill(definition, readText(instructions), description(resource)));
        }
        return List.copyOf(loaded);
    }

    private List<Resource> resolve(List<String> locations, String kind) {
        if (locations == null || locations.isEmpty()) {
            throw new DiagnosticDefinitionException("No declarative " + kind + " locations configured");
        }
        Map<String, Resource> unique = new LinkedHashMap<>();
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                throw new DiagnosticDefinitionException("Declarative " + kind + " location must not be blank");
            }
            try {
                for (Resource resource : resources.getResources(location)) {
                    if (resource.exists() && resource.isReadable()) {
                        unique.putIfAbsent(description(resource), resource);
                    }
                }
            } catch (IOException exception) {
                throw new DiagnosticDefinitionException(
                        "Cannot scan declarative " + kind + " location: " + location, exception);
            }
        }
        return unique.values().stream().sorted(Comparator.comparing(this::description)).toList();
    }

    private <T> T readYaml(Resource resource, Class<T> type) {
        try (InputStream input = resource.getInputStream()) {
            return yamlMapper.readValue(input, type);
        } catch (IOException | RuntimeException exception) {
            throw new DiagnosticDefinitionException(
                    "Cannot parse declarative YAML " + description(resource) + ": " + exception.getMessage(), exception);
        }
    }

    private String readText(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new DiagnosticDefinitionException(
                    "Cannot read skill instructions " + description(resource), exception);
        }
    }

    private Resource relative(Resource resource, String relativePath) {
        try {
            Resource target = resource.createRelative(relativePath);
            if (!target.exists() || !target.isReadable()) {
                throw new DiagnosticDefinitionException(
                        "Missing " + relativePath + " next to " + description(resource));
            }
            return target;
        } catch (IOException exception) {
            throw new DiagnosticDefinitionException(
                    "Cannot resolve " + relativePath + " next to " + description(resource), exception);
        }
    }

    private String description(Resource resource) {
        try {
            return resource.getURL().toExternalForm();
        } catch (IOException exception) {
            return resource.getDescription();
        }
    }

    private static ObjectMapper strictYamlMapper() {
        YAMLFactory factory = YAMLFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return new ObjectMapper(factory)
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }
}
