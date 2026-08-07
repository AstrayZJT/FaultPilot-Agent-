package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultpilot.observability")
public class ObservabilityProperties {

    private String prometheusUrl = "http://localhost:9090";
    private int timeoutSeconds = 5;
    private double processCpuHighThreshold = 0.80;
    private double threadPoolSaturationRatio = 0.90;
    private double heapUsageHighRatio = 0.90;
    private double httpLatencyHighSeconds = 1.0;
    private double redisCommandLatencyHighSeconds = 0.20;
    private double redisMemoryUsageHighRatio = 0.90;
    private long redisEvictionsHighThreshold = 1;
    private long redisSlowCommandThresholdMicros = 10_000;
    private long databaseSlowQueryThresholdMillis = 1_000;
    private long databaseHoldingQueryThresholdMillis = 1_000;
    private long traceSlowSpanThresholdMillis = 1_000;

    public String getPrometheusUrl() {
        return prometheusUrl;
    }

    public void setPrometheusUrl(String prometheusUrl) {
        this.prometheusUrl = prometheusUrl;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public double getProcessCpuHighThreshold() {
        return processCpuHighThreshold;
    }

    public void setProcessCpuHighThreshold(double processCpuHighThreshold) {
        this.processCpuHighThreshold = processCpuHighThreshold;
    }

    public double getThreadPoolSaturationRatio() {
        return threadPoolSaturationRatio;
    }

    public void setThreadPoolSaturationRatio(double threadPoolSaturationRatio) {
        this.threadPoolSaturationRatio = threadPoolSaturationRatio;
    }

    public double getHeapUsageHighRatio() {
        return heapUsageHighRatio;
    }

    public void setHeapUsageHighRatio(double heapUsageHighRatio) {
        this.heapUsageHighRatio = heapUsageHighRatio;
    }

    public double getHttpLatencyHighSeconds() {
        return httpLatencyHighSeconds;
    }

    public void setHttpLatencyHighSeconds(double httpLatencyHighSeconds) {
        this.httpLatencyHighSeconds = httpLatencyHighSeconds;
    }

    public double getRedisCommandLatencyHighSeconds() {
        return redisCommandLatencyHighSeconds;
    }

    public void setRedisCommandLatencyHighSeconds(double redisCommandLatencyHighSeconds) {
        this.redisCommandLatencyHighSeconds = redisCommandLatencyHighSeconds;
    }

    public double getRedisMemoryUsageHighRatio() {
        return redisMemoryUsageHighRatio;
    }

    public void setRedisMemoryUsageHighRatio(double redisMemoryUsageHighRatio) {
        this.redisMemoryUsageHighRatio = redisMemoryUsageHighRatio;
    }

    public long getRedisEvictionsHighThreshold() {
        return redisEvictionsHighThreshold;
    }

    public void setRedisEvictionsHighThreshold(long redisEvictionsHighThreshold) {
        this.redisEvictionsHighThreshold = redisEvictionsHighThreshold;
    }

    public long getRedisSlowCommandThresholdMicros() {
        return redisSlowCommandThresholdMicros;
    }

    public void setRedisSlowCommandThresholdMicros(long redisSlowCommandThresholdMicros) {
        this.redisSlowCommandThresholdMicros = redisSlowCommandThresholdMicros;
    }

    public long getDatabaseSlowQueryThresholdMillis() {
        return databaseSlowQueryThresholdMillis;
    }

    public void setDatabaseSlowQueryThresholdMillis(long databaseSlowQueryThresholdMillis) {
        this.databaseSlowQueryThresholdMillis = databaseSlowQueryThresholdMillis;
    }

    public long getDatabaseHoldingQueryThresholdMillis() {
        return databaseHoldingQueryThresholdMillis;
    }

    public void setDatabaseHoldingQueryThresholdMillis(long databaseHoldingQueryThresholdMillis) {
        this.databaseHoldingQueryThresholdMillis = databaseHoldingQueryThresholdMillis;
    }

    public long getTraceSlowSpanThresholdMillis() {
        return traceSlowSpanThresholdMillis;
    }

    public void setTraceSlowSpanThresholdMillis(long traceSlowSpanThresholdMillis) {
        this.traceSlowSpanThresholdMillis = traceSlowSpanThresholdMillis;
    }
}
