package com.astrayzjt.faultpilot.runbook;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runbooks")
public class RunbookController {
    private final RunbookService service;

    public RunbookController(RunbookService service) {
        this.service = service;
    }

    @GetMapping("/search")
    public List<RunbookDocument> search(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(required = false) String causeCode) {
        return service.search(q, causeCode);
    }
}
