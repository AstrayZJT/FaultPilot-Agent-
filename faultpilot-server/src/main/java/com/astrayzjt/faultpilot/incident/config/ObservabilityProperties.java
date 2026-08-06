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
}
