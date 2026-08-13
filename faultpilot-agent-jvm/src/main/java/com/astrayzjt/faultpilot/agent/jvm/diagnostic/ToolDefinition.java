package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.List;
import java.util.Map;

public record ToolDefinition(String apiVersion, String kind, DefinitionMetadata metadata, Spec spec) {

    public record Spec(String ownerAgent, String description, Endpoint endpoint, Request request,
                       Response response, Limits limits, String riskLevel) {
    }

    public record Endpoint(String ref, String path, HttpMethod method) {
    }

    public record Request(Prometheus prometheus, Map<String, ValueBinding> query,
                          Map<String, ValueBinding> body) {
        public Request {
            query = query == null ? Map.of() : Map.copyOf(query);
            body = body == null ? Map.of() : Map.copyOf(body);
        }
    }

    public record Prometheus(String queryTemplate) {
    }

    public record ValueBinding(String source, Object value) {
    }

    public record Response(ResponseParser parser, String resultPath, String valuePath,
                           Aggregation aggregation, EvidenceType emptyStatus, EvidenceMapping evidence) {
    }

    public record EvidenceMapping(EvidenceRule rule, String thresholdFrom, EvidenceType trueType,
                                  EvidenceType falseType, String trueSummary, String falseSummary,
                                  String sourceTemplate) {
    }

    public record Limits(int timeoutSeconds, int maxResponseBytes, int maxItems) {
    }

    public enum HttpMethod { GET, POST }

    public enum ResponseParser { JSON, ARTHAS_HOT_THREADS, ARTHAS_WAITING_THREADS }

    public enum EvidenceRule { THRESHOLD, NON_EMPTY, BOOLEAN }

    public enum Aggregation { MAX, MIN, SUM, AVG, COUNT, FIRST }
}
