package com.astrayzjt.faultpilot.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "faultpilot.orchestration")
public class OrchestrationProperties {

    private Mode mode = Mode.LOOP;
    private int maxRounds = 4;
    private int maxDelegations = 8;
    private int runTimeoutSeconds = 180;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.LOOP : mode;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(int maxRounds) {
        if (maxRounds < 1 || maxRounds > 10) {
            throw new IllegalArgumentException("Main Agent maxRounds must be between 1 and 10");
        }
        this.maxRounds = maxRounds;
    }

    public int getMaxDelegations() {
        return maxDelegations;
    }

    public void setMaxDelegations(int maxDelegations) {
        if (maxDelegations < 1 || maxDelegations > 20) {
            throw new IllegalArgumentException("Main Agent maxDelegations must be between 1 and 20");
        }
        this.maxDelegations = maxDelegations;
    }

    public int getRunTimeoutSeconds() {
        return runTimeoutSeconds;
    }

    public void setRunTimeoutSeconds(int runTimeoutSeconds) {
        if (runTimeoutSeconds < 30 || runTimeoutSeconds > 1_800) {
            throw new IllegalArgumentException("Investigation run timeout must be between 30 and 1800 seconds");
        }
        this.runTimeoutSeconds = runTimeoutSeconds;
    }

    public enum Mode {
        LOOP,
        LEGACY
    }
}
