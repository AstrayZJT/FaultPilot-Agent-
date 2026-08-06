package com.astrayzjt.faultpilot.lab.order.api;

import com.astrayzjt.faultpilot.lab.order.fault.FaultScenarioManager;
import com.astrayzjt.faultpilot.lab.order.fault.ScenarioRun;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/lab")
public class ScenarioController {

    private final FaultScenarioManager manager;

    public ScenarioController(FaultScenarioManager manager) {
        this.manager = manager;
    }

    @PostMapping("/scenarios/{scenarioCode}/inject")
    public ScenarioRun inject(@PathVariable String scenarioCode, @RequestBody(required = false) ScenarioRequest request) {
        return manager.inject(scenarioCode, request == null ? null : request.ttlSeconds(),
                request == null ? null : request.startedBy());
    }

    @PostMapping("/scenario-runs/{scenarioRunId}/recover")
    public ResponseEntity<ScenarioRun> recover(@PathVariable UUID scenarioRunId) {
        ScenarioRun result = manager.recover(scenarioRunId);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/scenario-runs")
    public List<ScenarioRun> list() {
        return manager.listRuns();
    }

    public record ScenarioRequest(Long ttlSeconds, String startedBy) {
    }
}

