package com.astrayzjt.faultpilot.triage;

import com.astrayzjt.faultpilot.common.domain.AgentType;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;
import com.astrayzjt.faultpilot.common.domain.RoutingSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class RoutingAdvisor {

    public List<RoutingSignal> derive(List<Evidence> evidence) {
        Map<AgentType, Score> scores = new EnumMap<>(AgentType.class);
        for (Evidence item : evidence) {
            contribution(item.type()).ifPresent(contribution -> scores
                    .computeIfAbsent(contribution.agentType(), ignored -> new Score(contribution.reasonCode()))
                    .add(contribution.score(), item.evidenceId()));
        }
        return scores.entrySet().stream()
                .map(entry -> new RoutingSignal(entry.getKey(), entry.getValue().score, entry.getValue().evidenceIds,
                        entry.getValue().reasonCode))
                .sorted(Comparator.comparingInt(RoutingSignal::score).reversed().thenComparing(signal -> signal.agentType().name()))
                .toList();
    }

    private java.util.Optional<Contribution> contribution(EvidenceType type) {
        return switch (type) {
            case PROCESS_CPU_HIGH, THREAD_POOL_ACTIVE_AT_MAX, THREAD_POOL_QUEUE_GROWING, BLOCKING_TASK_FOUND,
                    REPEATED_RUNNABLE_STACK, CPU_HOT_METHOD_FOUND -> java.util.Optional.of(new Contribution(AgentType.JVM_AGENT, 5, "JVM_ANOMALY"));
            case PROCESS_CPU_NORMAL, THREAD_POOL_NORMAL -> java.util.Optional.of(new Contribution(AgentType.JVM_AGENT, -3, "JVM_NORMAL"));
            case DB_POOL_PENDING_HIGH, DB_POOL_ACTIVE_AT_MAX, CONNECTION_HOLDING_QUERY_FOUND, SLOW_SQL_FOUND,
                    ABNORMAL_EXECUTION_PLAN, API_AND_SQL_TIME_CORRELATED -> java.util.Optional.of(new Contribution(AgentType.DATABASE_AGENT, 5, "DATABASE_ANOMALY"));
            case DOWNSTREAM_LATENCY_HIGH, SLOW_CHILD_SPAN_FOUND -> java.util.Optional.of(new Contribution(AgentType.DEPENDENCY_AGENT, 5, "DEPENDENCY_ANOMALY"));
            case REDIS_COMMAND_LATENCY_HIGH, REDIS_CLIENT_POOL_PENDING_HIGH, REDIS_MEMORY_PRESSURE,
                    REDIS_EVICTIONS_HIGH, REDIS_CACHE_HIT_RATE_LOW, REDIS_SLOW_COMMAND_FOUND,
                    REDIS_TRACE_LATENCY_CORRELATED -> java.util.Optional.of(new Contribution(AgentType.CACHE_AGENT, 5, "CACHE_ANOMALY"));
            case REDIS_COMMAND_LATENCY_NORMAL, REDIS_CLIENT_POOL_NORMAL -> java.util.Optional.of(new Contribution(AgentType.CACHE_AGENT, -3, "CACHE_NORMAL"));
            default -> java.util.Optional.empty();
        };
    }

    private record Contribution(AgentType agentType, int score, String reasonCode) {
    }

    private static final class Score {
        private int score;
        private final String reasonCode;
        private final List<UUID> evidenceIds = new ArrayList<>();

        private Score(String reasonCode) {
            this.reasonCode = reasonCode;
        }

        private void add(int contribution, UUID evidenceId) {
            score += contribution;
            evidenceIds.add(evidenceId);
        }
    }
}
