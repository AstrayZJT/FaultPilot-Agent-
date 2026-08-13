package com.astrayzjt.faultpilot.evidence;

import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceRepositoryTest {

    @Test
    void contentReuseStillPersistsAReceiptForTheNewToolCall() {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID delegationId = UUID.randomUUID();
        Evidence existing = evidence(incidentId, runId, UUID.randomUUID(), "call-old");
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(List.of(
                List.of(),
                List.of(),
                List.of(existing)));
        EvidenceRepository repository = new EvidenceRepository(jdbc, new ObjectMapper());
        Evidence incoming = evidence(incidentId, runId, delegationId, "call-new");

        Evidence saved = repository.saveOrReuseDelegated(incoming, delegationId);

        assertThat(saved.evidenceId()).isEqualTo(existing.evidenceId());
        assertThat(jdbc.updateSql).anyMatch(sql -> sql.contains("delegated_tool_call_receipt"));
        assertThat(jdbc.updateSql).anyMatch(sql -> sql.contains("agent_delegation_evidence_link"));
    }

    @Test
    void delegatedEvidenceRequiresRunAndToolCallIdentity() {
        UUID incidentId = UUID.randomUUID();
        UUID delegationId = UUID.randomUUID();
        Evidence withoutRun = evidence(incidentId, null, delegationId, "call-1");
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(List.of());
        EvidenceRepository repository = new EvidenceRepository(jdbc, new ObjectMapper());

        assertThatThrownBy(() -> repository.saveOrReuseDelegated(withoutRun, delegationId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Run and Tool call ID");
    }

    private Evidence evidence(UUID incidentId, UUID runId, UUID taskId, String toolCallId) {
        Instant now = Instant.now();
        return new Evidence(UUID.randomUUID(), incidentId, taskId, runId, "jvm-agent", "query_cpu", toolCallId,
                "1.0.0", EvidenceStatus.ACTIVE, EvidenceType.PROCESS_CPU_HIGH, "prometheus:order-service:cpu",
                "order-service", now.minusSeconds(30), now, "CPU is high", null, "same-content", java.util.Map.of(), now);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<List<?>> queryResults;
        private final List<String> updateSql = new ArrayList<>();
        private int queryIndex;

        private RecordingJdbcTemplate(List<List<?>> queryResults) {
            this.queryResults = queryResults;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return (List<T>) queryResults.get(queryIndex++);
        }

        @Override
        public int update(String sql, Object... args) {
            updateSql.add(sql);
            return 1;
        }
    }
}
