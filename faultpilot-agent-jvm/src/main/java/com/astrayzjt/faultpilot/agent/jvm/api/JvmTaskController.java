package com.astrayzjt.faultpilot.agent.jvm.api;

import com.astrayzjt.faultpilot.agent.jvm.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.task.JvmTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/a2a/tasks")
public class JvmTaskController {

    private final JvmTaskService service;

    public JvmTaskController(JvmTaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<A2aTaskSnapshot> submit(@RequestBody DelegationRequest request) {
        A2aTaskSnapshot snapshot = service.submit(request);
        return ResponseEntity.accepted().location(URI.create("/a2a/tasks/" + snapshot.remoteTaskId()))
                .body(snapshot);
    }

    @GetMapping("/{remoteTaskId}")
    public A2aTaskSnapshot query(@PathVariable UUID remoteTaskId) {
        return service.query(remoteTaskId).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
    }

    @DeleteMapping("/{remoteTaskId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID remoteTaskId) {
        if (!service.cancel(remoteTaskId)) {
            throw new ResponseStatusException(NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }
}
