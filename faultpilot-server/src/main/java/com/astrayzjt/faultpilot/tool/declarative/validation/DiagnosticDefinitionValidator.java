package com.astrayzjt.faultpilot.tool.declarative.validation;

import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedSkill;
import com.astrayzjt.faultpilot.tool.declarative.model.LoadedTool;
import com.astrayzjt.faultpilot.tool.declarative.model.SkillDefinition;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class DiagnosticDefinitionValidator {

    private static final String API_VERSION = "faultpilot/v1";
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{1,127}");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][a-zA-Z0-9.-]+)?");
    private static final Pattern ENDPOINT_REF = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Pattern INPUT_NAME = Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,63}");
    private static final Pattern METRIC = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Pattern MATCHER = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*(?:=~|!~|!=|=)\\\"(?:\\\\.|[^\\\"\\\\\\r\\n])*\\\"");
    private static final Pattern DATA_PATH = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*|\\[[0-9]+])*");
    private static final Pattern THRESHOLD = Pattern.compile("observability\\.[a-zA-Z][a-zA-Z0-9]{1,127}");
    private static final Set<String> INPUT_TYPES = Set.of("object", "string", "integer", "number", "boolean");
    private static final Set<String> INPUT_SOURCES = Set.of("task.serviceName");

    public void validate(List<LoadedTool> loadedTools, List<LoadedSkill> loadedSkills,
                         Set<String> allowedEndpointRefs) {
        require(loadedTools != null && !loadedTools.isEmpty(), "At least one declarative tool is required");
        require(loadedSkills != null && !loadedSkills.isEmpty(), "At least one diagnostic skill is required");
        require(allowedEndpointRefs != null && !allowedEndpointRefs.isEmpty(),
                "At least one endpoint.ref must be allowlisted");

        loadedTools.forEach(tool -> validateTool(tool, allowedEndpointRefs));
        rejectDuplicateSources(loadedTools, item -> item.definition().metadata().name(), "tool");

        Map<String, ToolDefinition> tools = loadedTools.stream().collect(Collectors.toUnmodifiableMap(
                item -> item.definition().metadata().name(), LoadedTool::definition));
        loadedSkills.forEach(skill -> validateSkill(skill, tools));
        rejectDuplicateSources(loadedSkills, item -> item.definition().metadata().name(), "skill");
    }

    void validateTool(LoadedTool loaded, Set<String> allowedEndpointRefs) {
        ToolDefinition definition = requireValue(loaded.definition(), "Tool definition", loaded.source());
        require(API_VERSION.equals(definition.apiVersion()), "Unsupported tool apiVersion", loaded.source());
        require("DiagnosticTool".equals(definition.kind()), "Tool kind must be DiagnosticTool", loaded.source());
        validateMetadata(definition.metadata(), loaded.source());
        ToolDefinition.Spec spec = requireValue(definition.spec(), "Tool spec", loaded.source());
        requireValue(spec.ownerAgent(), "Tool ownerAgent", loaded.source());
        requireText(spec.description(), "Tool description", loaded.source(), 512);
        require(spec.riskLevel() == ToolRisk.READ_ONLY, "Only READ_ONLY tools can be loaded", loaded.source());

        ToolDefinition.Endpoint endpoint = requireValue(spec.endpoint(), "Tool endpoint", loaded.source());
        require(endpoint.ref() != null && ENDPOINT_REF.matcher(endpoint.ref()).matches(),
                "Invalid endpoint.ref", loaded.source());
        require(allowedEndpointRefs.contains(endpoint.ref()), "Unknown endpoint.ref: " + endpoint.ref(), loaded.source());
        validateFixedPath(endpoint.path(), loaded.source());
        requireValue(endpoint.method(), "Tool endpoint.method", loaded.source());

        validateInput(spec.input(), loaded.source());
        validateRequest(spec.request(), endpoint.method(), loaded.source());
        validateResponse(spec.response(), loaded.source());
        validateLimits(spec.limits(), loaded.source());
    }

    void validateSkill(LoadedSkill loaded, Map<String, ToolDefinition> tools) {
        SkillDefinition definition = requireValue(loaded.definition(), "Skill definition", loaded.source());
        require(API_VERSION.equals(definition.apiVersion()), "Unsupported skill apiVersion", loaded.source());
        require("DiagnosticSkill".equals(definition.kind()), "Skill kind must be DiagnosticSkill", loaded.source());
        validateMetadata(definition.metadata(), loaded.source());
        SkillDefinition.Spec spec = requireValue(definition.spec(), "Skill spec", loaded.source());
        requireValue(spec.ownerAgent(), "Skill ownerAgent", loaded.source());
        requireText(spec.description(), "Skill description", loaded.source(), 512);
        require(spec.riskLevel() == ToolRisk.READ_ONLY, "Only READ_ONLY skills can be loaded", loaded.source());
        requireUnique(spec.triggerEvidenceTypes(), "triggerEvidenceTypes", loaded.source());
        requireUnique(spec.producesEvidenceTypes(), "producesEvidenceTypes", loaded.source());
        require(!spec.allowedTools().isEmpty(), "Skill allowedTools must not be empty", loaded.source());
        requireUnique(spec.allowedTools(), "allowedTools", loaded.source());

        Set<EvidenceType> producedByTools = new LinkedHashSet<>();
        for (String toolName : spec.allowedTools()) {
            ToolDefinition tool = tools.get(toolName);
            require(tool != null, "Skill references unknown tool: " + toolName, loaded.source());
            require(tool.spec().ownerAgent() == spec.ownerAgent(),
                    "Skill references a tool owned by another agent: " + toolName, loaded.source());
            ToolDefinition.EvidenceMapping mapping = tool.spec().response().evidence();
            if (mapping.trueType() != null) {
                producedByTools.add(mapping.trueType());
            }
            if (mapping.falseType() != null) {
                producedByTools.add(mapping.falseType());
            }
        }
        require(producedByTools.containsAll(spec.producesEvidenceTypes()),
                "Skill declares Evidence types not produced by its tools", loaded.source());

        SkillDefinition.Completion completion = requireValue(spec.completion(), "Skill completion", loaded.source());
        require(!completion.allOf().isEmpty() || !completion.anyOf().isEmpty(),
                "Skill completion must define allOf or anyOf", loaded.source());
        requireUnique(completion.allOf(), "completion.allOf", loaded.source());
        requireUnique(completion.anyOf(), "completion.anyOf", loaded.source());
        Set<EvidenceType> knownEvidence = new HashSet<>(spec.triggerEvidenceTypes());
        knownEvidence.addAll(spec.producesEvidenceTypes());
        require(knownEvidence.containsAll(completion.allOf()) && knownEvidence.containsAll(completion.anyOf()),
                "Skill completion references undeclared Evidence types", loaded.source());

        SkillDefinition.Limits limits = requireValue(spec.limits(), "Skill limits", loaded.source());
        require(limits.maxSteps() >= 1 && limits.maxSteps() <= 10,
                "Skill maxSteps must be between 1 and 10", loaded.source());
        require(limits.timeoutSeconds() >= 1 && limits.timeoutSeconds() <= 300,
                "Skill timeoutSeconds must be between 1 and 300", loaded.source());
        requireText(loaded.instructions(), "SKILL.md instructions", loaded.source(), 32_768);
    }

    private void validateMetadata(com.astrayzjt.faultpilot.tool.declarative.model.DefinitionMetadata metadata,
                                  String source) {
        requireValue(metadata, "metadata", source);
        require(metadata.name() != null && NAME.matcher(metadata.name()).matches(), "Invalid metadata.name", source);
        require(metadata.version() != null && VERSION.matcher(metadata.version()).matches(),
                "metadata.version must be semantic version", source);
    }

    private void validateInput(ToolDefinition.Input input, String source) {
        if (input == null) {
            return;
        }
        require("object".equals(input.type()), "Tool input.type must be object", source);
        requireUnique(input.required(), "input.required", source);
        require(input.properties().keySet().containsAll(input.required()),
                "Every required input must have a property definition", source);
        input.properties().forEach((name, property) -> {
            require(INPUT_NAME.matcher(name).matches(), "Invalid input property: " + name, source);
            require(property != null && INPUT_TYPES.contains(property.type()),
                    "Invalid input type for " + name, source);
            require(INPUT_SOURCES.contains(property.source()),
                    "Input source is not supported by the current execution context: " + property.source(), source);
            require(property.maxLength() == null || property.maxLength() >= 1 && property.maxLength() <= 1024,
                    "Input maxLength must be between 1 and 1024", source);
        });
    }

    private void validateRequest(ToolDefinition.Request request, ToolDefinition.HttpMethod method, String source) {
        requireValue(request, "Tool request", source);
        if (request.prometheus() != null) {
            ToolDefinition.Prometheus prometheus = request.prometheus();
            require(prometheus.metric() != null && METRIC.matcher(prometheus.metric()).matches(),
                    "Prometheus metric is not allowlisted", source);
            require("service.prometheusLabels".equals(prometheus.selectorFrom()),
                    "Prometheus selectorFrom must be service.prometheusLabels", source);
            requireUnique(prometheus.extraMatchers(), "Prometheus extraMatchers", source);
            prometheus.extraMatchers().forEach(matcher -> require(matcher != null && MATCHER.matcher(matcher).matches(),
                    "Invalid fixed Prometheus matcher: " + matcher, source));
            require(method == ToolDefinition.HttpMethod.GET, "Prometheus tools must use GET", source);
        }
        validateBindings(request.query(), "query", source);
        validateBindings(request.body(), "body", source);
        require(method != ToolDefinition.HttpMethod.GET || request.body().isEmpty(),
                "GET tools cannot define a request body", source);
        require(request.prometheus() != null || !request.query().isEmpty() || !request.body().isEmpty(),
                "Tool request must define a supported request mapping", source);
    }

    private void validateBindings(Map<String, ToolDefinition.ValueBinding> bindings, String location, String source) {
        bindings.forEach((name, binding) -> {
            require(name != null && INPUT_NAME.matcher(name).matches(), "Invalid " + location + " field: " + name, source);
            require(binding != null, "Missing binding for " + location + " field: " + name, source);
            boolean hasSource = binding.source() != null && !binding.source().isBlank();
            boolean hasValue = binding.value() != null;
            require(hasSource ^ hasValue, "A binding must define exactly one of source or value", source);
            if (hasSource) {
                require(INPUT_SOURCES.contains(binding.source()),
                        "Binding source is not supported by the current execution context: " + binding.source(), source);
            } else {
                require(binding.value() instanceof String || binding.value() instanceof Number
                                || binding.value() instanceof Boolean,
                        "Fixed binding values must be scalar", source);
            }
        });
    }

    private void validateResponse(ToolDefinition.Response response, String source) {
        requireValue(response, "Tool response", source);
        require(response.resultPath() != null && DATA_PATH.matcher(response.resultPath()).matches(),
                "Invalid response.resultPath", source);
        require(response.valuePath() == null || DATA_PATH.matcher(response.valuePath()).matches(),
                "Invalid response.valuePath", source);
        requireValue(response.aggregation(), "response.aggregation", source);
        ToolDefinition.EvidenceMapping evidence = requireValue(response.evidence(), "response.evidence", source);
        requireValue(evidence.rule(), "response.evidence.rule", source);
        require(evidence.trueType() != null, "response.evidence.trueType is required", source);
        requireText(evidence.trueSummary(), "response.evidence.trueSummary", source, 512);
        if (evidence.rule() == ToolDefinition.EvidenceRule.THRESHOLD) {
            require(evidence.thresholdFrom() != null && THRESHOLD.matcher(evidence.thresholdFrom()).matches(),
                    "Threshold rule requires an allowlisted thresholdFrom", source);
        } else {
            require(evidence.thresholdFrom() == null || evidence.thresholdFrom().isBlank(),
                    "Only threshold rules can define thresholdFrom", source);
        }
        if (evidence.falseType() != null) {
            requireText(evidence.falseSummary(), "response.evidence.falseSummary", source, 512);
        }
        if (evidence.sourceTemplate() != null) {
            require(!evidence.sourceTemplate().contains("://") && evidence.sourceTemplate().length() <= 256,
                    "Evidence sourceTemplate cannot contain a URL", source);
        }
    }

    private void validateLimits(ToolDefinition.Limits limits, String source) {
        requireValue(limits, "Tool limits", source);
        require(limits.timeoutSeconds() >= 1 && limits.timeoutSeconds() <= 30,
                "Tool timeoutSeconds must be between 1 and 30", source);
        require(limits.maxResponseBytes() >= 1_024 && limits.maxResponseBytes() <= 1_048_576,
                "Tool maxResponseBytes must be between 1024 and 1048576", source);
        require(limits.maxItems() >= 1 && limits.maxItems() <= 1_000,
                "Tool maxItems must be between 1 and 1000", source);
    }

    private void validateFixedPath(String path, String source) {
        require(path != null && path.startsWith("/"), "Endpoint path must start with /", source);
        require(path.length() <= 256 && !path.contains("://") && !path.contains("..")
                        && !path.contains("?") && !path.contains("#") && !path.contains("{") && !path.contains("}"),
                "Endpoint path must be a fixed relative path", source);
    }

    private <T> void rejectDuplicateSources(List<T> items, Function<T, String> name, String kind) {
        Map<String, Integer> counts = new HashMap<>();
        items.forEach(item -> counts.merge(name.apply(item), 1, Integer::sum));
        counts.forEach((value, count) -> require(count == 1, "Duplicate " + kind + ": " + value));
    }

    private <T> void requireUnique(List<T> values, String field, String source) {
        require(values != null && new HashSet<>(values).size() == values.size(),
                field + " must not contain duplicates", source);
        require(values == null || values.stream().noneMatch(java.util.Objects::isNull),
                field + " must not contain null", source);
    }

    private <T> T requireValue(T value, String field, String source) {
        require(value != null, field + " is required", source);
        return value;
    }

    private void requireText(String value, String field, String source, int maxLength) {
        require(value != null && !value.isBlank() && value.length() <= maxLength,
                field + " must contain 1-" + maxLength + " characters", source);
    }

    private void require(boolean condition, String message, String source) {
        if (!condition) {
            throw new DiagnosticDefinitionException(source + ": " + message);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new DiagnosticDefinitionException(message);
        }
    }
}
