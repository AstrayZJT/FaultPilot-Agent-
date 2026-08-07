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

    public UUID proposalId() {
        return UUID.fromString(value("proposalId", ""));
    }

    public UUID critiqueId() {
        return UUID.fromString(value("critiqueId", ""));
    }

    public int revision() {
        return ((Number) value("revision", 0)).intValue();
    }

    public String critiqueVerdict() {
        return value("critiqueVerdict", "");
    }

    public String gateStatus() {
        return value("gateStatus", "");
    }
}
