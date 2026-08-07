package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.incident.application.IncidentService;
import com.astrayzjt.faultpilot.incident.application.InvestigationDetailService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/incidents/{incidentId}/investigation")
public class InvestigationDetailController {

    private final IncidentService incidentService;
    private final InvestigationDetailService detailService;

    public InvestigationDetailController(IncidentService incidentService, InvestigationDetailService detailService) {
        this.incidentService = incidentService;
        this.detailService = detailService;
    }

    @GetMapping
    public ResponseEntity<InvestigationDetail> detail(@PathVariable UUID incidentId) {
        if (incidentService.find(incidentId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detailService.find(incidentId));
    }
}
