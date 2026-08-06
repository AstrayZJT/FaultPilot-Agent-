package com.astrayzjt.faultpilot.common.web;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(
        Instant timestamp,
        String traceId,
        int status,
        String code,
        String message,
        String path,
        Map<String, String> details) {
}

