package com.astrayzjt.faultpilot.orchestration;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LoopIncidentGraphState extends AgentState {

    public LoopIncidentGraphState(Map<String, Object> data) {
        super(data);
    }

    public UUID incidentId() {
        return UUID.fromString(value("incidentId", ""));
    }

    public UUID runId() {
        return UUID.fromString(value("runId", ""));
    }

    public int round() {
        return ((Number) value("round", 0)).intValue();
    }

    public String action() {
        return value("action", "");
    }

    public String delegationsJson() {
        return value("delegationsJson", "[]");
    }

    @SuppressWarnings("unchecked")
    public List<String> evidenceIds() {
        return (List<String>) value("evidenceIds", List.<String>of());
    }

    public String diagnosisDraftJson() {
        return value("diagnosisDraftJson", "");
    }

    public String outcome() {
        return value("outcome", "RUNNING");
    }
}
