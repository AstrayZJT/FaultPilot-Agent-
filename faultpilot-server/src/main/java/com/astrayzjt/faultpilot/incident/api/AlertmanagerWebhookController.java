package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.common.domain.Incident;
import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.persistence.IncidentRepository;
import com.astrayzjt.faultpilot.orchestration.IncidentOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/integrations/alertmanager")
public class AlertmanagerWebhookController {

    private final IncidentService incidentService;
    private final IncidentRepository incidentRepository;
    private final IncidentOrchestrator orchestrator;

    public AlertmanagerWebhookController(IncidentService incidentService, IncidentRepository incidentRepository,
                                         IncidentOrchestrator orchestrator) {
        this.incidentService = incidentService;
        this.incidentRepository = incidentRepository;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> receive(@RequestBody Map<String, Object> payload) {
        if (!"firing".equalsIgnoreCase(String.valueOf(payload.getOrDefault("status", "")))) {
            return ResponseEntity.accepted().body(Map.of("status", "ignored"));
        }
        Map<String, Object> labels = payload.get("commonLabels") instanceof Map<?, ?> common
                ? cast(common) : payload.get("labels") instanceof Map<?, ?> direct ? cast(direct) : Map.of();
        String serviceName = String.valueOf(labels.getOrDefault("service", labels.get("serviceName")));
        if (serviceName == null || "null".equals(serviceName) || serviceName.isBlank()) {
            throw new IllegalArgumentException("Alert is missing trusted service label");
        }
        String fingerprint = String.valueOf(payload.getOrDefault("fingerprint", ""));
        String startsAt = String.valueOf(payload.getOrDefault("startsAt", ""));
        if (fingerprint.isBlank() || startsAt.isBlank()) {
            throw new IllegalArgumentException("Alert is missing fingerprint or startsAt");
        }
        String externalRef = fingerprint + ":" + startsAt;
        var existing = incidentRepository.findIdByExternalRef("ALERTMANAGER", externalRef);
        if (existing.isPresent()) {
            return ResponseEntity.accepted().body(Map.of("incidentId", existing.get(), "status", "EXISTING"));
        }
        Instant start = parseInstant(startsAt);
        IncidentRequest request = new IncidentRequest(serviceName, String.valueOf(payload.getOrDefault("message", "Alertmanager firing alert")),
                fingerprint, start, Instant.now(), null, null, null, false);
        Incident incident = incidentService.create(request, "ALERTMANAGER", externalRef);
        orchestrator.start(incident.incidentId());
        return ResponseEntity.accepted().body(Map.of("incidentId", incident.incidentId(), "status", incident.status()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> value) {
        return value.entrySet().stream().collect(java.util.stream.Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            return Instant.now().minusSeconds(600);
        }
    }
}
