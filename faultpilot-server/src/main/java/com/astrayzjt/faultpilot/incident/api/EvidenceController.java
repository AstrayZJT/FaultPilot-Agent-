package com.astrayzjt.faultpilot.incident.api;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents/{incidentId}/evidence")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @GetMapping
    public List<Evidence> list(@PathVariable UUID incidentId) {
        return evidenceService.findByIncident(incidentId);
    }
}

