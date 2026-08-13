package com.astrayzjt.faultpilot.evidence;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.tool.registry.ToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class EvidenceService {

    private final EvidenceRepository repository;
    private final ObjectMapper objectMapper;

    public EvidenceService(EvidenceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Evidence record(UUID incidentId, UUID taskId, ToolResult result, Instant windowStart, Instant windowEnd) {
        return record(null, incidentId, taskId, null, null, null, null, result, windowStart, windowEnd);
    }

    public Evidence record(UUID runId, UUID incidentId, UUID taskId, String agentId, String capabilityVersion,
                           String toolId, String toolCallId, ToolResult result,
                           Instant windowStart, Instant windowEnd) {
        EvidenceType type = result.evidenceType();
        if (type == null) {
            return null;
        }
        String content = result.summary() + "|" + serialize(result.data());
        Evidence evidence = new Evidence(UUID.randomUUID(), incidentId, taskId, runId, agentId, toolId,
                toolCallId, capabilityVersion, EvidenceStatus.ACTIVE, type, result.source(), result.source(),
                windowStart, windowEnd, result.summary(), null, sha256(content), result.data(), Instant.now());
        return repository.saveOrReuse(evidence);
    }

    public List<Evidence> findByIncident(UUID incidentId) {
        return repository.findByIncident(incidentId);
    }

    public List<Evidence> findActiveByRun(UUID runId) {
        return repository.findActiveByRun(runId);
    }

    public List<Evidence> findActiveByRunAndTask(UUID runId, UUID taskId) {
        return repository.findActiveByRunAndTask(runId, taskId);
    }

    public void markRunStale(UUID runId) {
        repository.markRunStale(runId);
    }

    public void linkTaskEvidence(UUID taskId, UUID evidenceId, String usage) {
        if (taskId != null && evidenceId != null) {
            repository.linkTaskEvidence(taskId, evidenceId, usage);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
