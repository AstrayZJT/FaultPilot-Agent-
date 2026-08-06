package com.astrayzjt.faultpilot.common.domain;

import java.time.Duration;
import java.time.Instant;

public record TimeRange(Instant start, Instant end) {
    public TimeRange {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}

