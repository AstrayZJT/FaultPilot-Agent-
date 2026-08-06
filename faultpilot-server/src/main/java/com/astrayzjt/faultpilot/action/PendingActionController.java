package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.PendingAction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pending-actions")
public class PendingActionController {

    private final RemediationService service;

    public PendingActionController(RemediationService service) {
        this.service = service;
    }

    @GetMapping("/{actionId}")
    public PendingAction get(@PathVariable UUID actionId) {
        return service.find(actionId);
    }

    @GetMapping("/incident/{incidentId}")
    public List<PendingAction> listByIncident(@PathVariable UUID incidentId) {
        return service.findByIncident(incidentId);
    }

    @PostMapping("/{actionId}/confirm")
    public ResponseEntity<PendingAction> confirm(@PathVariable UUID actionId,
                                                 @RequestHeader("X-Request-Id") String requestId,
                                                 Principal principal) {
        String user = principal == null ? "operator" : principal.getName();
        return ResponseEntity.accepted().body(service.confirm(actionId, user, requestId));
    }

    @PostMapping("/{actionId}/reject")
    public PendingAction reject(@PathVariable UUID actionId, Principal principal) {
        return service.reject(actionId, principal == null ? "operator" : principal.getName());
    }
}
