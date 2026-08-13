package com.astrayzjt.faultpilot.agent.jvm.diagnostic;

public enum EvidenceType {
    PROCESS_CPU_HIGH,
    PROCESS_CPU_NORMAL,
    THREAD_POOL_ACTIVE_AT_MAX,
    THREAD_POOL_QUEUE_GROWING,
    THREAD_POOL_NORMAL,
    CPU_HOT_METHOD_FOUND,
    BLOCKING_TASK_FOUND,
    DATA_UNAVAILABLE
}
