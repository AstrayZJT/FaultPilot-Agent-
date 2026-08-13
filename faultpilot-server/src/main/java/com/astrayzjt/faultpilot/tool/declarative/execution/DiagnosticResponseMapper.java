package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.declarative.model.ToolDefinition;
import com.astrayzjt.faultpilot.tool.registry.ToolExecutionContext;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DiagnosticResponseMapper {

    private final ObjectMapper objectMapper;
    private final DiagnosticThresholdCatalog thresholds;

    public DiagnosticResponseMapper(ObjectMapper objectMapper, DiagnosticThresholdCatalog thresholds) {
        this.objectMapper = objectMapper.copy();
        this.objectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(32)
                .maxStringLength(262_144)
                .maxNumberLength(128)
                .build());
        this.thresholds = thresholds;
    }

    public ToolExecutionResult map(ToolDefinition definition, DiagnosticHttpResponse response,
                                   ToolExecutionContext context) {
        String source = source(definition, context);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return ToolExecutionResult.failure(response.statusCode(), source,
                    "Diagnostic endpoint returned HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (IOException exception) {
            return ToolExecutionResult.failure(response.statusCode(), source,
                    "Diagnostic endpoint returned invalid JSON");
        }
        if (root == null) {
            return ToolExecutionResult.failure(response.statusCode(), source,
                    "Diagnostic endpoint returned an empty response");
        }

        ToolDefinition.Response mapping = definition.spec().response();
        JsonNode result = resolve(root, mapping.resultPath());
        if (isEmpty(result)) {
            EvidenceType emptyType = mapping.emptyStatus() == null
                    ? EvidenceType.DATA_UNAVAILABLE : mapping.emptyStatus();
            return new ToolExecutionResult(false, response.statusCode(),
                    "Diagnostic endpoint returned no matching data", Map.of(), emptyType, source);
        }

        return switch (mapping.evidence().rule()) {
            case THRESHOLD -> threshold(definition, result, response.statusCode(), context, source);
            case NON_EMPTY -> nonEmpty(definition, result, response.statusCode(), source);
            case BOOLEAN -> booleanResult(definition, result, response.statusCode(), source);
        };
    }

    private ToolExecutionResult threshold(ToolDefinition definition, JsonNode result, int status,
                                          ToolExecutionContext context, String source) {
        ToolDefinition.Response response = definition.spec().response();
        List<Double> values = numericValues(result, response.valuePath(), definition.spec().limits().maxItems());
        if (values.isEmpty()) {
            return ToolExecutionResult.failure(status, source, "Diagnostic response contained no numeric sample");
        }
        double aggregated = aggregate(values, response.aggregation());
        double threshold = thresholds.require(response.evidence().thresholdFrom());
        boolean matches = aggregated >= threshold;
        ToolDefinition.EvidenceMapping evidence = response.evidence();
        EvidenceType type = matches ? evidence.trueType() : evidence.falseType();
        String summary = matches ? evidence.trueSummary() : evidence.falseSummary();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("value", aggregated);
        data.put("threshold", threshold);
        data.put("sampleCount", values.size());
        if (definition.spec().request().prometheus() != null) {
            data.put("metric", definition.spec().request().prometheus().metric());
        }
        return new ToolExecutionResult(true, status, summary == null ? "Diagnostic threshold evaluated" : summary,
                data, type, source);
    }

    private ToolExecutionResult nonEmpty(ToolDefinition definition, JsonNode result, int status, String source) {
        int maxItems = definition.spec().limits().maxItems();
        JsonNode bounded = bound(result, maxItems);
        int count = bounded.isArray() ? bounded.size() : 1;
        ToolDefinition.EvidenceMapping evidence = definition.spec().response().evidence();
        Map<String, Object> data = Map.of(
                "count", count,
                "result", objectMapper.convertValue(bounded, Object.class));
        return new ToolExecutionResult(true, status, evidence.trueSummary(), data, evidence.trueType(), source);
    }

    private ToolExecutionResult booleanResult(ToolDefinition definition, JsonNode result, int status, String source) {
        boolean value = result.asBoolean(false);
        ToolDefinition.EvidenceMapping evidence = definition.spec().response().evidence();
        EvidenceType type = value ? evidence.trueType() : evidence.falseType();
        String summary = value ? evidence.trueSummary() : evidence.falseSummary();
        return new ToolExecutionResult(true, status, summary == null ? "Diagnostic boolean evaluated" : summary,
                Map.of("value", value), type, source);
    }

    private List<Double> numericValues(JsonNode result, String valuePath, int maxItems) {
        List<Double> values = new ArrayList<>();
        if (result.isArray()) {
            for (int index = 0; index < result.size() && index < maxItems; index++) {
                addNumber(values, valuePath == null ? result.get(index) : resolve(result.get(index), valuePath));
            }
        } else {
            addNumber(values, valuePath == null ? result : resolve(result, valuePath));
        }
        return List.copyOf(values);
    }

    private void addNumber(List<Double> values, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        try {
            double value = node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText());
            if (Double.isFinite(value)) {
                values.add(value);
            }
        } catch (NumberFormatException ignored) {
            // Malformed samples are ignored; an all-malformed response becomes DATA_UNAVAILABLE.
        }
    }

    private double aggregate(List<Double> values, ToolDefinition.Aggregation aggregation) {
        return switch (aggregation) {
            case MAX -> values.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            case MIN -> values.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            case SUM -> values.stream().mapToDouble(Double::doubleValue).sum();
            case AVG -> values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            case COUNT -> values.size();
            case FIRST -> values.getFirst();
        };
    }

    private JsonNode resolve(JsonNode root, String path) {
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            int bracket = segment.indexOf('[');
            String field = bracket < 0 ? segment : segment.substring(0, bracket);
            current = current.path(field);
            while (bracket >= 0 && !current.isMissingNode()) {
                int end = segment.indexOf(']', bracket);
                int index = Integer.parseInt(segment.substring(bracket + 1, end));
                current = current.path(index);
                bracket = segment.indexOf('[', end + 1);
            }
        }
        return current;
    }

    private JsonNode bound(JsonNode value, int maxItems) {
        if (!value.isArray() || value.size() <= maxItems) {
            return value;
        }
        var bounded = objectMapper.createArrayNode();
        for (int index = 0; index < maxItems; index++) {
            bounded.add(value.get(index));
        }
        return bounded;
    }

    private boolean isEmpty(JsonNode value) {
        return value == null || value.isMissingNode() || value.isNull()
                || value.isArray() && value.isEmpty()
                || value.isObject() && value.isEmpty()
                || value.isTextual() && value.asText().isBlank();
    }

    private String source(ToolDefinition definition, ToolExecutionContext context) {
        String template = definition.spec().response().evidence().sourceTemplate();
        if (template != null && !template.isBlank()) {
            return template.replace("{serviceName}", context.serviceName());
        }
        return definition.spec().endpoint().ref() + ":" + context.serviceName() + ":"
                + definition.metadata().name().toLowerCase(Locale.ROOT);
    }
}
