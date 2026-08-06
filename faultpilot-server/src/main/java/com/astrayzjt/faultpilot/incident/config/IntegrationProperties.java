package com.astrayzjt.faultpilot.incident.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultpilot.integration")
public class IntegrationProperties {

    private Mode mode = Mode.LAB;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.LAB : mode;
    }

    public boolean isLab() {
        return mode == Mode.LAB;
    }

    public boolean isProductionReadOnly() {
        return mode == Mode.PRODUCTION_READ_ONLY;
    }

    public enum Mode {
        LAB,
        PRODUCTION_READ_ONLY
    }
}
