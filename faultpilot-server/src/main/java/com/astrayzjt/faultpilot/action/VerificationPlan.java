package com.astrayzjt.faultpilot.action;

import java.util.List;

public record VerificationPlan(List<String> checks) {
    public VerificationPlan {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
