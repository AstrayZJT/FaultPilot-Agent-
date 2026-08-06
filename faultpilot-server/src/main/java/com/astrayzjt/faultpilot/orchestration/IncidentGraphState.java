package com.astrayzjt.faultpilot.orchestration;

import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IncidentGraphState extends AgentState {

    public IncidentGraphState(Map<String, Object> data) {
        super(data);
    }

    public UUID incidentId() {
        return UUID.fromString(value("incidentId", ""));
    }

    public int round() {
        return ((Number) value("round", 0)).intValue();
    }

    @SuppressWarnings("unchecked")
    public List<String> plannedAgents() {
        return (List<String>) value("plannedAgents", List.<String>of());
    }

    public String outcome() {
        return value("outcome", "FOLLOW_UP");
    }
}
