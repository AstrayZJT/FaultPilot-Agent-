package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class JvmDiagnosticDefinitionLoader {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{2,127}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private final ResourcePatternResolver resolver;
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public JvmDiagnosticDefinitionLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public JvmDiagnosticCatalog load() {
        List<ToolDefinition> tools = resources("classpath*:diagnostic/tools/*.yaml").stream()
                .map(resource -> read(resource, ToolDefinition.class)).toList();
        List<LoadedSkill> skills = resources("classpath*:diagnostic/skills/*/SKILL.md").stream()
                .map(this::readSkill).toList();
        validate(tools, skills);
        return new JvmDiagnosticCatalog(tools, skills);
    }

    private LoadedSkill readSkill(Resource resource) {
        try {
            String source = description(resource);
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            SkillDocument document = splitSkillDocument(content, source);
            SkillDefinition definition = yamlMapper.readValue(document.frontMatter(), SkillDefinition.class);
            return new LoadedSkill(definition, document.instructions(), source);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot parse JVM diagnostic SKILL.md " + description(resource),
                    exception);
        }
    }

    private SkillDocument splitSkillDocument(String value, String source) {
        String document = value.startsWith("\uFEFF") ? value.substring(1) : value;
        document = document.replace("\r\n", "\n").replace('\r', '\n');
        if (!document.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md must start with YAML front matter: " + source);
        }
        int closing = document.indexOf("\n---\n", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("SKILL.md has unterminated YAML front matter: " + source);
        }
        String frontMatter = document.substring(4, closing).trim();
        String instructions = document.substring(closing + 5).strip();
        if (frontMatter.isBlank() || instructions.isBlank()) {
            throw new IllegalArgumentException(
                    "SKILL.md requires non-empty front matter and instructions: " + source);
        }
        return new SkillDocument(frontMatter, instructions);
    }

    private <T> T read(Resource resource, Class<T> type) {
        try (var input = resource.getInputStream()) {
            return yamlMapper.readValue(input, type);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot parse JVM diagnostic definition " + description(resource),
                    exception);
        }
    }

    private List<Resource> resources(String pattern) {
        try {
            List<Resource> values = new ArrayList<>(List.of(resolver.getResources(pattern)));
            values.sort(Comparator.comparing(this::description));
            if (values.isEmpty()) {
                throw new IllegalArgumentException("No JVM diagnostic definitions found for " + pattern);
            }
            return values;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot scan JVM diagnostic definitions", exception);
        }
    }

    private void validate(List<ToolDefinition> tools, List<LoadedSkill> skills) {
        if (tools.isEmpty() || skills.isEmpty()) {
            throw new IllegalArgumentException("JVM Agent requires at least one Tool and one Skill");
        }
        Map<String, ToolDefinition> byName = tools.stream().collect(java.util.stream.Collectors.toMap(
                tool -> tool.metadata().name(), java.util.function.Function.identity()));
        tools.forEach(tool -> validateTool(tool));
        skills.forEach(skill -> validateSkill(skill, byName));
    }

    private void validateTool(ToolDefinition tool) {
        require("faultpilot/v1".equals(tool.apiVersion()) && "DiagnosticTool".equals(tool.kind()),
                "Invalid JVM Tool envelope");
        validateMetadata(tool.metadata());
        ToolDefinition.Spec spec = requireValue(tool.spec(), "Tool spec");
        require("JVM_AGENT".equals(spec.ownerAgent()), "JVM module can load only JVM_AGENT Tools");
        require("READ_ONLY".equals(spec.riskLevel()), "JVM Agent accepts only READ_ONLY Tools");
        requireText(spec.description(), "Tool description", 512);
        ToolDefinition.Endpoint endpoint = requireValue(spec.endpoint(), "Tool endpoint");
        require(endpoint.ref() != null && endpoint.ref().matches("[a-z][a-z0-9-]{1,63}"),
                "Invalid endpoint ref");
        require(endpoint.path() != null && endpoint.path().startsWith("/") && !endpoint.path().contains("..")
                && !endpoint.path().contains("?") && !endpoint.path().contains("#"), "Invalid fixed Tool path");
        require(endpoint.method() != null, "Tool HTTP method is required");
        ToolDefinition.Request request = requireValue(spec.request(), "Tool request");
        if (request.prometheus() != null) {
            require(endpoint.method() == ToolDefinition.HttpMethod.GET, "Prometheus Tool must use GET");
            require(request.prometheus().queryTemplate() != null
                    && request.prometheus().queryTemplate().contains("${selector}"),
                    "Prometheus queryTemplate must contain ${selector}");
            require(request.prometheus().queryTemplate().length() <= 4_096
                    && request.prometheus().queryTemplate().chars().noneMatch(Character::isISOControl),
                    "Prometheus queryTemplate is too long or contains control characters");
        }
        request.body().forEach((name, binding) -> validateBinding(name, binding));
        request.query().forEach((name, binding) -> validateBinding(name, binding));
        require(endpoint.method() != ToolDefinition.HttpMethod.GET || request.body().isEmpty(),
                "GET Tool cannot define a body");
        ToolDefinition.Response response = requireValue(spec.response(), "Tool response");
        require(response.parser() != null && response.evidence() != null,
                "Tool response parser and Evidence mapping are required");
        validateReadOnlyParser(response.parser(), endpoint, request);
        require(response.evidence().trueType() != null, "Tool true Evidence type is required");
        requireText(response.evidence().trueSummary(), "Tool true summary", 512);
        if (response.evidence().rule() == ToolDefinition.EvidenceRule.THRESHOLD) {
            requireText(response.evidence().thresholdFrom(), "Tool thresholdFrom", 128);
        }
        ToolDefinition.Limits limits = requireValue(spec.limits(), "Tool limits");
        require(limits.timeoutSeconds() >= 1 && limits.timeoutSeconds() <= 30,
                "Tool timeout must be 1-30 seconds");
        require(limits.maxResponseBytes() >= 1024 && limits.maxResponseBytes() <= 1_048_576,
                "Tool response byte limit is invalid");
        require(limits.maxItems() >= 1 && limits.maxItems() <= 1000, "Tool maxItems is invalid");
    }

    private void validateReadOnlyParser(ToolDefinition.ResponseParser parser, ToolDefinition.Endpoint endpoint,
                                        ToolDefinition.Request request) {
        if (parser == ToolDefinition.ResponseParser.JSON) {
            require(request.prometheus() != null && "prometheus".equals(endpoint.ref()),
                    "JSON JVM metric Tools must use the Prometheus request binding");
            return;
        }
        require("arthas".equals(endpoint.ref()) && endpoint.method() == ToolDefinition.HttpMethod.POST
                && request.prometheus() == null, "Arthas parser requires the fixed Arthas POST endpoint");
        Object action = literal(request.body().get("action"));
        Object command = literal(request.body().get("command"));
        require("exec".equals(action), "Arthas Tool action must be the fixed exec action");
        String expected = parser == ToolDefinition.ResponseParser.ARTHAS_HOT_THREADS
                ? "thread -n 8" : "thread --state WAITING -n 50";
        require(expected.equals(command), "Arthas Tool command is not on the read-only allowlist");
    }

    private Object literal(ToolDefinition.ValueBinding binding) {
        return binding == null || binding.source() != null ? null : binding.value();
    }

    private void validateSkill(LoadedSkill loaded, Map<String, ToolDefinition> tools) {
        SkillDefinition skill = loaded.definition();
        require("faultpilot/v1".equals(skill.apiVersion()) && "DiagnosticSkill".equals(skill.kind()),
                "Invalid JVM Skill envelope");
        validateMetadata(skill.metadata());
        SkillDefinition.Spec spec = requireValue(skill.spec(), "Skill spec");
        require("JVM_AGENT".equals(spec.ownerAgent()), "JVM module can load only JVM_AGENT Skills");
        require("READ_ONLY".equals(spec.riskLevel()), "JVM Agent accepts only READ_ONLY Skills");
        requireText(spec.description(), "Skill description", 512);
        require(!spec.allowedTools().isEmpty() && new HashSet<>(spec.allowedTools()).size() == spec.allowedTools().size(),
                "Skill Tool whitelist is empty or duplicated");
        Set<EvidenceType> produced = new HashSet<>();
        spec.allowedTools().forEach(name -> {
            ToolDefinition tool = tools.get(name);
            require(tool != null, "Skill references unknown Tool " + name);
            produced.add(tool.spec().response().evidence().trueType());
            if (tool.spec().response().evidence().falseType() != null) {
                produced.add(tool.spec().response().evidence().falseType());
            }
        });
        require(produced.containsAll(spec.producesEvidenceTypes()),
                "Skill produces Evidence not exposed by its Tools");
        Set<EvidenceType> declared = new HashSet<>(spec.triggerEvidenceTypes());
        declared.addAll(spec.producesEvidenceTypes());
        require(spec.completion() != null && (!spec.completion().allOf().isEmpty()
                || !spec.completion().anyOf().isEmpty()), "Skill completion is empty");
        require(declared.containsAll(spec.completion().allOf()) && declared.containsAll(spec.completion().anyOf()),
                "Skill completion references undeclared Evidence");
        require(spec.limits() != null && spec.limits().maxSteps() >= 1 && spec.limits().maxSteps() <= 10
                && spec.limits().timeoutSeconds() >= 1 && spec.limits().timeoutSeconds() <= 300,
                "Skill limits are invalid");
        requireText(loaded.instructions(), "SKILL.md", 32_768);
    }

    private void validateBinding(String name, ToolDefinition.ValueBinding binding) {
        require(name != null && name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}") && binding != null,
                "Invalid Tool request binding");
        boolean source = binding.source() != null && !binding.source().isBlank();
        boolean value = binding.value() != null;
        require(source ^ value, "Tool binding must define exactly one source or value");
        if (source) {
            require("task.serviceName".equals(binding.source()), "Unsupported Tool binding source");
        }
    }

    private void validateMetadata(DefinitionMetadata metadata) {
        require(metadata != null && metadata.name() != null && NAME.matcher(metadata.name()).matches(),
                "Invalid definition name");
        require(metadata.version() != null && VERSION.matcher(metadata.version()).matches(),
                "Definition version must be semantic");
    }

    private <T> T requireValue(T value, String field) {
        require(value != null, field + " is required");
        return value;
    }

    private void requireText(String value, String field, int maxLength) {
        require(value != null && !value.isBlank() && value.length() <= maxLength,
                field + " must contain 1-" + maxLength + " characters");
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private String description(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException exception) {
            return resource.getDescription();
        }
    }

    private record SkillDocument(String frontMatter, String instructions) {
    }
}
