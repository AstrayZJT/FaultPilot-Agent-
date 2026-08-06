package com.astrayzjt.faultpilot.evaluation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {
    private final EvaluationService service;

    public EvaluationController(EvaluationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, String> request) {
        UUID id = service.start(request == null ? "RULE" : request.get("mode"));
        return ResponseEntity.accepted().body(Map.of("evaluationRunId", id, "status", "RUNNING"));
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable UUID runId) {
        return service.find(runId);
    }

    @GetMapping("/cases")
    public Object cases() {
        return service.definitions();
    }
}
