package com.astrayzjt.faultpilot.agent.jvm.protocol;

import java.time.Instant;

public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid Incident time range");
        }
    }
}
