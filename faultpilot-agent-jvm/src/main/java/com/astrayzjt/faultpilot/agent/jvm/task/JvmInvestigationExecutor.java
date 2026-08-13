package com.astrayzjt.faultpilot.agent.jvm.task;

import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;

import java.util.function.BooleanSupplier;

public interface JvmInvestigationExecutor {
    TaskOutcome execute(DelegationRequest request, JvmTaskProgress progress, BooleanSupplier canceled,
                        JvmInvestigationProgressRecorder recorder);
}
