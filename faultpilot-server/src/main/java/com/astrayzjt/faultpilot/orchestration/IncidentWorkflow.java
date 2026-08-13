package com.astrayzjt.faultpilot.orchestration;

import java.util.UUID;

public interface IncidentWorkflow {
    void start(UUID incidentId);
}
