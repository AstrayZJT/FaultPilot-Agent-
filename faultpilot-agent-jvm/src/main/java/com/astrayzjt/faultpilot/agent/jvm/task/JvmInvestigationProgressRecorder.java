package com.astrayzjt.faultpilot.agent.jvm.task;

import java.util.UUID;

public interface JvmInvestigationProgressRecorder {

    void selectSkill(String skillName);

    JvmToolCallRecord reserveTool(String toolName);

    void completeTool(JvmToolCallRecord call, UUID evidenceId);
}
