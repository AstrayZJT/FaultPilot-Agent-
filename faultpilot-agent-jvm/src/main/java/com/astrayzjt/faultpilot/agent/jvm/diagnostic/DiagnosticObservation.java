package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

import java.util.Map;

public record DiagnosticObservation(boolean success, String summary, Map<String, Object> data,
                                    EvidenceType evidenceType, String source) {
    public DiagnosticObservation {
        summary = summary == null ? "" : summary;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public static DiagnosticObservation unavailable(String source, String summary) {
        return new DiagnosticObservation(false, summary, Map.of(), EvidenceType.DATA_UNAVAILABLE, source);
    }
}
