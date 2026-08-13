package com.astrayzjt.faultpilot.tool.declarative.execution;

import com.astrayzjt.faultpilot.incident.config.ObservabilityProperties;

import java.util.Map;
import java.util.function.DoubleSupplier;

public final class DiagnosticThresholdCatalog {

    private final Map<String, DoubleSupplier> thresholds;

    public DiagnosticThresholdCatalog(ObservabilityProperties properties) {
        this.thresholds = Map.ofEntries(
                Map.entry("observability.processCpuHighThreshold", properties::getProcessCpuHighThreshold),
                Map.entry("observability.threadPoolSaturationRatio", properties::getThreadPoolSaturationRatio),
                Map.entry("observability.heapUsageHighRatio", properties::getHeapUsageHighRatio),
                Map.entry("observability.httpLatencyHighSeconds", properties::getHttpLatencyHighSeconds),
                Map.entry("observability.redisCommandLatencyHighSeconds", properties::getRedisCommandLatencyHighSeconds),
                Map.entry("observability.redisMemoryUsageHighRatio", properties::getRedisMemoryUsageHighRatio),
                Map.entry("observability.redisEvictionsHighThreshold",
                        () -> properties.getRedisEvictionsHighThreshold()),
                Map.entry("observability.redisCacheHitRateLowRatio", properties::getRedisCacheHitRateLowRatio),
                Map.entry("observability.databaseSlowQueryThresholdMillis",
                        () -> properties.getDatabaseSlowQueryThresholdMillis()),
                Map.entry("observability.databaseHoldingQueryThresholdMillis",
                        () -> properties.getDatabaseHoldingQueryThresholdMillis()),
                Map.entry("observability.traceSlowSpanThresholdMillis",
                        () -> properties.getTraceSlowSpanThresholdMillis()));
    }

    public double require(String reference) {
        DoubleSupplier supplier = thresholds.get(reference);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown diagnostic threshold reference: " + reference);
        }
        return supplier.getAsDouble();
    }
}
