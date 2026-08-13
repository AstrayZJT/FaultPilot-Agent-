package com.astrayzjt.faultpilot.agent.distributed.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

public final class DelegationIdentity {

    private DelegationIdentity() {
    }

    public static String normalizeObjective(String objective) {
        if (objective == null || objective.isBlank()) {
            throw new IllegalArgumentException("Delegation objective must not be blank");
        }
        String normalized = Normalizer.normalize(objective, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.length() > 2_000) {
            throw new IllegalArgumentException("Delegation objective must not exceed 2000 characters");
        }
        return normalized;
    }

    public static String objectiveHash(String objective) {
        return sha256(normalizeObjective(objective));
    }

    public static String idempotencyKey(UUID runId, int round, String agentId, String objective) {
        if (runId == null || round < 1 || agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("Run, round and Agent ID are required for delegation idempotency");
        }
        return runId + ":" + round + ":" + agentId.trim().toLowerCase(Locale.ROOT) + ":"
                + objectiveHash(objective);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
