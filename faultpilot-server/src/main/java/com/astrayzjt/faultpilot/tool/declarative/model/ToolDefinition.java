package com.astrayzjt.faultpilot.tool.declarative.model;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.registry.ToolRisk;
import java.util.List;
import java.util.Map;

public record ToolDefinition(
        String apiVersion,
        String kind,
        DefinitionMetadata metadata,
        Spec spec) {

    public record Spec(
            AgentType ownerAgent,
            String description,
            Endpoint endpoint,
            Input input,
            Request request,
            Response response,
            Limits limits,
            ToolRisk riskLevel) {
    }

    public record Endpoint(String ref, String path, HttpMethod method) {
    }

    public record Input(String type, List<String> required, Map<String, InputProperty> properties) {
        public Input {
            required = required == null ? List.of() : List.copyOf(required);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }
    }

    public record InputProperty(String type, String source, Integer maxLength) {
    }

    public record Request(
            Prometheus prometheus,
            Map<String, ValueBinding> query,
            Map<String, ValueBinding> body) {
        public Request {
            query = query == null ? Map.of() : Map.copyOf(query);
            body = body == null ? Map.of() : Map.copyOf(body);
        }
    }

    public record Prometheus(String metric, String selectorFrom, List<String> extraMatchers) {
        public Prometheus {
            extraMatchers = extraMatchers == null ? List.of() : List.copyOf(extraMatchers);
        }
    }

    public record ValueBinding(String source, Object value) {
    }

    public record Response(
            String resultPath,
            String valuePath,
            Aggregation aggregation,
            EvidenceType emptyStatus,
            EvidenceMapping evidence) {
    }

    public record EvidenceMapping(
            EvidenceRule rule,
            String thresholdFrom,
            EvidenceType trueType,
            EvidenceType falseType,
            String trueSummary,
            String falseSummary,
            String sourceTemplate) {
    }

    public record Limits(int timeoutSeconds, int maxResponseBytes, int maxItems) {
    }

    public enum Aggregation {
        MAX,
        MIN,
        SUM,
        AVG,
        COUNT,
        FIRST
    }

    public enum EvidenceRule {
        THRESHOLD,
        NON_EMPTY,
        BOOLEAN
    }

    public enum HttpMethod {
        GET,
        POST
    }
}
