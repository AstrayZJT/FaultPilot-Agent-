package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.orchestration.IncidentOrchestrator;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final IncidentOrchestrator orchestrator;

    public IncidentController(IncidentService incidentService, IncidentOrchestrator orchestrator) {
        this.incidentService = incidentService;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody IncidentRequest request) {
        Incident incident = incidentService.create(request);
        orchestrator.start(incident.incidentId());
        return ResponseEntity.accepted().location(URI.create("/api/incidents/" + incident.incidentId()))
                .body(Map.of("incidentId", incident.incidentId(), "status", incident.status()));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<Incident> get(@PathVariable UUID incidentId) {
        return incidentService.find(incidentId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
