package com.astrayzjt.faultpilot.action;

import com.astrayzjt.faultpilot.common.domain.ActionCode;
import com.astrayzjt.faultpilot.common.domain.RiskLevel;

public interface RemediationAction<A> {
    ActionCode code();
    RiskLevel riskLevel();
    Class<A> argumentType();
    ActionResult execute(A arguments, ActionExecutionContext context);
    boolean verify(A arguments, ActionExecutionContext context);
    VerificationPlan verificationPlan();
}
