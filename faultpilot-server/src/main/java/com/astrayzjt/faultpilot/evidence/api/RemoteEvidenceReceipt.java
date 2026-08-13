package com.astrayzjt.faultpilot.evidence.api;

import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;

import java.util.UUID;

public record RemoteEvidenceReceipt(String schemaVersion, UUID evidenceId, EvidenceStatus status) {

    public static final String SCHEMA_VERSION = "faultpilot.evidence-receipt/v1";
}
