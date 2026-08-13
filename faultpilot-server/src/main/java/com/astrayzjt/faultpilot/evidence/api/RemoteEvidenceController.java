package com.astrayzjt.faultpilot.evidence.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/internal/evidence")
public final class RemoteEvidenceController {

    private final RemoteEvidenceService service;

    public RemoteEvidenceController(RemoteEvidenceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<RemoteEvidenceReceipt> record(@RequestBody RemoteEvidenceWriteRequest request) {
        RemoteEvidenceReceipt receipt = service.record(request);
        return ResponseEntity.created(URI.create("/api/incidents/" + request.incidentId() + "/evidence"))
                .body(receipt);
    }

    @PostMapping("/query")
    public List<RemoteEvidenceView> query(@RequestBody RemoteEvidenceQueryRequest request) {
        return service.query(request);
    }
}
