package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;
import com.astrayzjt.faultpilot.common.domain.RiskLevel;
import com.astrayzjt.faultpilot.incident.config.ServiceCatalogProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Configuration
@ConditionalOnProperty(prefix = "faultpilot.integration", name = "mode", havingValue = "LAB", matchIfMissing = true)
public class LabRemediationActionsConfiguration {

    @Bean
    RemediationAction<Map<String, Object>> stopCpuFault(ServiceCatalogProperties catalog, ObjectMapper mapper) {
        return labAction(ActionCode.STOP_CPU_FAULT, "order-service", "CPU_HOTSPOT", "cpuHotspot", catalog, mapper);
    }

    @Bean
    RemediationAction<Map<String, Object>> releaseBlockedTasks(ServiceCatalogProperties catalog, ObjectMapper mapper) {
        return labAction(ActionCode.RELEASE_BLOCKED_TASKS, "order-service", "THREAD_POOL_EXHAUSTED", "threadPoolExhausted", catalog, mapper);
    }

    @Bean
    RemediationAction<Map<String, Object>> restoreIndexedQuery(ServiceCatalogProperties catalog, ObjectMapper mapper) {
        return labAction(ActionCode.RESTORE_INDEXED_QUERY, "order-service", "SLOW_SQL", "slowSql", catalog, mapper);
    }

    @Bean
    RemediationAction<Map<String, Object>> releaseHeldConnections(ServiceCatalogProperties catalog, ObjectMapper mapper) {
        return labAction(ActionCode.RELEASE_HELD_CONNECTIONS, "order-service", "DB_POOL_EXHAUSTED", "dbPoolExhausted", catalog, mapper);
    }

    @Bean
    RemediationAction<Map<String, Object>> restoreDependencyLatency(ServiceCatalogProperties catalog, ObjectMapper mapper) {
        return labAction(ActionCode.RESTORE_DEPENDENCY_LATENCY, "inventory-service", "DEPENDENCY_TIMEOUT", "dependencyDelay", catalog, mapper);
    }

    private RemediationAction<Map<String, Object>> labAction(ActionCode code, String service, String scenarioCode,
                                                              String verificationSignal, ServiceCatalogProperties catalog,
                                                              ObjectMapper mapper) {
        return new RemediationAction<>() {
            @Override
            public ActionCode code() {
                return code;
            }

            @Override
            public RiskLevel riskLevel() {
                return RiskLevel.HIGH;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Class<Map<String, Object>> argumentType() {
                return (Class<Map<String, Object>>) (Class<?>) Map.class;
            }

            @Override
            public ActionResult execute(Map<String, Object> arguments, ActionExecutionContext context) {
                context.throwIfExpired();
                String baseUrl = catalog.require(service).actuatorBaseUrl();
                Map<?, ?> result = RestClient.create(baseUrl).post()
                        .uri("/api/lab/scenarios/{scenarioCode}/recover-active", scenarioCode)
                        .retrieve().body(Map.class);
                if (result == null) {
                    return ActionResult.failure("No active matching lab scenario was found");
                }
                Map<String, Object> details = new java.util.LinkedHashMap<>();
                if (result != null) {
                    result.forEach((key, value) -> details.put(String.valueOf(key), value));
                }
                return new ActionResult(true, "Recovered lab scenario " + scenarioCode, details);
            }

            @Override
            public boolean verify(Map<String, Object> arguments, ActionExecutionContext context) {
                context.throwIfExpired();
                String baseUrl = catalog.require(service).actuatorBaseUrl();
                String path = service.equals("inventory-service")
                        ? "/api/inventory/internal/diagnostics" : "/api/orders/internal/diagnostics";
                Map<?, ?> diagnostics = RestClient.create(baseUrl).get().uri(path).retrieve().body(Map.class);
                return diagnostics != null && !Boolean.TRUE.equals(diagnostics.get(verificationSignal));
            }

            @Override
            public VerificationPlan verificationPlan() {
                return new VerificationPlan(List.of("lab diagnostic signal " + verificationSignal + " is false"));
            }
        };
    }

}
