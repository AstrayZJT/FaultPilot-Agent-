package com.astrayzjt.faultpilot.agent.jvm.reasoning;

import com.astrayzjt.faultpilot.agent.jvm.diagnostic.LoadedSkill;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.SkillSummary;
import com.astrayzjt.faultpilot.agent.jvm.diagnostic.ToolSummary;
import com.astrayzjt.faultpilot.agent.jvm.evidence.RemoteEvidenceView;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;

import java.util.List;
import java.util.Set;

public interface JvmReasoningModel {

    SkillDecision chooseSkill(DelegationRequest task, List<RemoteEvidenceView> evidence,
                              List<SkillSummary> candidates);

    StepDecision nextStep(DelegationRequest task, LoadedSkill skill, List<RemoteEvidenceView> evidence,
                          List<ToolSummary> tools, Set<String> calledTools, int step);
}
