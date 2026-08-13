package com.astrayzjt.faultpilot.agent.jvm.evidence;

import java.util.UUID;

public record RemoteEvidenceReceipt(String schemaVersion, UUID evidenceId, String status) {
}
