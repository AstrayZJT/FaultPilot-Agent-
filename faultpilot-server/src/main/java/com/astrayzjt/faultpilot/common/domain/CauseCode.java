package com.astrayzjt.faultpilot.common.domain;

public enum CauseCode {
    JVM_CPU_HOTSPOT,
    JVM_THREAD_POOL_EXHAUSTED,
    DB_SLOW_QUERY,
    DB_POOL_EXHAUSTED,
    DEPENDENCY_TIMEOUT,
    UNKNOWN
}

