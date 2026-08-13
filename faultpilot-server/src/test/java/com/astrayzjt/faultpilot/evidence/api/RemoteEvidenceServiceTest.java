package com.astrayzjt.faultpilot.evidence.api;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.domain.DelegationStatus;
import com.astrayzjt.faultpilot.agent.distributed.persistence.AgentDelegationRepository;
import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceStatus;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.evidence.EvidenceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteEvidenceServiceTest {

    @Test
    void recordsEvidenceOnlyForMatchingActiveDelegation() {
        Fixture fixture = fixture(DelegationStatus.RUNNING);
        RemoteEvidenceWriteRequest request = fixture.writeRequest();
        Evidence evidence = fixture.evidence(UUID.randomUUID());
        when(fixture.evidenceService.recordDelegated(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(evidence);

        RemoteEvidenceReceipt receipt = fixture.service.record(request);

        assertThat(receipt.evidenceId()).isEqualTo(evidence.evidenceId());
        assertThat(receipt.status()).isEqualTo(EvidenceStatus.ACTIVE);
        verify(fixture.evidenceService).recordDelegated(fixture.runId, fixture.incidentId, fixture.taskId,
                "jvm-agent", "jvm-1.0.0", "query_arthas_hot_threads", "call-1", new com.astrayzjt.faultpilot.tool.registry.ToolResult(
                        true, "Hot method found", Map.of("method", "OrderService.spin"),
                        EvidenceType.CPU_HOT_METHOD_FOUND, "arthas:order-service:hot-threads"),
                request.windowStart(), request.windowEnd());
    }

    @Test
    void rejectsIdentityMismatchAndTerminalDelegation() {
        Fixture fixture = fixture(DelegationStatus.RUNNING);
        RemoteEvidenceWriteRequest value = fixture.writeRequest();
        RemoteEvidenceWriteRequest wrongAgent = new RemoteEvidenceWriteRequest(value.schemaVersion(),
                value.incidentId(), value.runId(), value.taskId(), "database-agent", value.capabilityVersion(),
                value.toolId(), value.toolCallId(), value.success(), value.evidenceType(), value.source(),
                value.summary(), value.structuredData(), value.windowStart(), value.windowEnd());

        assertThatThrownBy(() -> fixture.service.record(wrongAgent))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity");

        Fixture terminal = fixture(DelegationStatus.FAILED);
        assertThatThrownBy(() -> terminal.service.record(terminal.writeRequest()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Terminal");
    }

    @Test
    void returnsOnlyRequestedEvidenceFromCurrentRun() {
        Fixture fixture = fixture(DelegationStatus.RUNNING);
        Evidence requested = fixture.evidence(UUID.randomUUID());
        Evidence other = fixture.evidence(UUID.randomUUID());
        when(fixture.evidenceService.findActiveByRun(fixture.runId)).thenReturn(List.of(requested, other));
        RemoteEvidenceQueryRequest query = new RemoteEvidenceQueryRequest(RemoteEvidenceQueryRequest.SCHEMA_VERSION,
                fixture.incidentId, fixture.runId, fixture.taskId, List.of(requested.evidenceId()));

        List<RemoteEvidenceView> result = fixture.service.query(query);

        assertThat(result).singleElement().extracting(RemoteEvidenceView::evidenceId)
                .isEqualTo(requested.evidenceId());
    }

    @Test
    void rejectsEvidenceIdsOutsideCurrentRun() {
        Fixture fixture = fixture(DelegationStatus.RUNNING);
        when(fixture.evidenceService.findActiveByRun(fixture.runId)).thenReturn(List.of());
        RemoteEvidenceQueryRequest query = new RemoteEvidenceQueryRequest(RemoteEvidenceQueryRequest.SCHEMA_VERSION,
                fixture.incidentId, fixture.runId, fixture.taskId, List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> fixture.service.query(query))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside");
    }

    private Fixture fixture(DelegationStatus status) {
        UUID incidentId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AgentDelegationRepository repository = mock(AgentDelegationRepository.class);
        EvidenceService evidenceService = mock(EvidenceService.class);
        Instant now = Instant.now();
        AgentDelegation delegation = new AgentDelegation(taskId, runId, incidentId, 1, "jvm-agent",
                AgentType.JVM_AGENT, "jvm-1.0.0", "Find CPU hotspot", "hash", "key", "remote-1", status,
                null, now.minusSeconds(1), status.terminal() ? now : null,
                status == DelegationStatus.FAILED ? "FAILED" : null,
                status == DelegationStatus.FAILED ? "failed" : null, 1);
        when(repository.find(taskId)).thenReturn(Optional.of(delegation));
        return new Fixture(new RemoteEvidenceService(repository, evidenceService), evidenceService,
                incidentId, runId, taskId, now);
    }

    private record Fixture(RemoteEvidenceService service, EvidenceService evidenceService, UUID incidentId,
                           UUID runId, UUID taskId, Instant now) {

        private RemoteEvidenceWriteRequest writeRequest() {
            return new RemoteEvidenceWriteRequest(RemoteEvidenceWriteRequest.SCHEMA_VERSION, incidentId, runId,
                    taskId, "jvm-agent", "jvm-1.0.0", "query_arthas_hot_threads", "call-1", true,
                    EvidenceType.CPU_HOT_METHOD_FOUND, "arthas:order-service:hot-threads", "Hot method found",
                    Map.of("method", "OrderService.spin"), now.minusSeconds(60), now);
        }

        private Evidence evidence(UUID evidenceId) {
            return new Evidence(evidenceId, incidentId, taskId, runId, "jvm-agent",
                    "query_arthas_hot_threads", "call-1", "jvm-1.0.0", EvidenceStatus.ACTIVE,
                    EvidenceType.CPU_HOT_METHOD_FOUND, "arthas:order-service:hot-threads", "order-service",
                    now.minusSeconds(60), now, "Hot method found", null, "hash", Map.of(), now);
        }
    }
}
