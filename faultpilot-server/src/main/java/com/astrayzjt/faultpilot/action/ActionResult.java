package com.astrayzjt.faultpilot.action;

import java.util.Map;

public record ActionResult(boolean success, String summary, Map<String, Object> details) {
    public static ActionResult failure(String summary) {
        return new ActionResult(false, summary, Map.of());
    }
}
