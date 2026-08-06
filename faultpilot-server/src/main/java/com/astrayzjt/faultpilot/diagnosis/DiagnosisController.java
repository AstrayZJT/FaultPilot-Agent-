package com.astrayzjt.faultpilot.diagnosis;

import com.astrayzjt.faultpilot.common.domain.DiagnosisDecision;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/incidents/{incidentId}/report")
public class DiagnosisController {

    private final DiagnosisRepository repository;

    public DiagnosisController(DiagnosisRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<DiagnosisDecision> report(@PathVariable UUID incidentId) {
        return repository.find(incidentId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}

