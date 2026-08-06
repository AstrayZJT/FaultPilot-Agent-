package com.astrayzjt.faultpilot.runbook;

import java.time.Instant;
import java.util.UUID;

public record RunbookDocument(UUID id, String title, String content, String causeCode, boolean reviewed,
                              Instant updatedAt) {
}
