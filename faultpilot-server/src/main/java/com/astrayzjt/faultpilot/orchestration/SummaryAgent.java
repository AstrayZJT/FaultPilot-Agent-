package com.astrayzjt.faultpilot.orchestration;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;
import com.astrayzjt.faultpilot.common.domain.ModelRole;
import com.astrayzjt.faultpilot.common.model.RemoteModelClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class SummaryAgent {

    private final RemoteModelClient modelClient;
    private final ObjectMapper objectMapper;

    public SummaryAgent(RemoteModelClient modelClient, ObjectMapper objectMapper) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
    }

    public SummaryResult summarize(IncidentSnapshot incident, DiagnosisDraft draft, List<Evidence> evidence) {
        String system = "You are FaultPilot Summary Agent. Write a concise diagnosis summary using only the supplied " +
                "validated DiagnosisDraft and Evidence. You cannot change status, root cause, contributing factors, " +
                "or Evidence IDs. Return JSON only: {\"summary\":\"...\"}.";
        String user = "incident=" + json(incident) + "\nvalidatedDiagnosis=" + json(draft)
                + "\nevidence=" + json(evidence);
        String raw;
        try {
            raw = modelClient.complete(incident.incidentId(), null, ModelRole.SUMMARY,
                    "summary-v1", system, user, 500);
        } catch (RuntimeException unavailable) {
            return fallback(draft);
        }
        try {
            return new SummaryResult(parse(raw), false);
        } catch (RuntimeException invalidOutput) {
            try {
                String repaired = modelClient.complete(incident.incidentId(), null, ModelRole.SUMMARY,
                        "summary-repair-v1", "Return one JSON object only with one non-empty string field named summary.",
                        raw, 500);
                return new SummaryResult(parse(repaired), false);
            } catch (RuntimeException ignored) {
                return fallback(draft);
            }
        }
    }

    private SummaryResult fallback(DiagnosisDraft draft) {
        String fallback = draft.summary().isBlank()
                ? draft.primaryCause().name() + " is supported by the cited Evidence"
                : draft.summary();
        return new SummaryResult(fallback, true);
    }

    String parse(String raw) {
        try {
            int start = raw == null ? -1 : raw.indexOf('{');
            int end = raw == null ? -1 : raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("No JSON object in Summary Agent output");
            }
            String summary = objectMapper.readTree(raw.substring(start, end + 1)).path("summary").asText("").trim();
            if (summary.isBlank() || summary.length() > 2_000) {
                throw new IllegalArgumentException("Summary must contain 1-2000 characters");
            }
            return summary;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Summary Agent output is not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Summary Agent context", exception);
        }
    }

    public record SummaryResult(String summary, boolean fallbackUsed) {
    }
}
