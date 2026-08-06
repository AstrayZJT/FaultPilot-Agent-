package com.astrayzjt.faultpilot.evaluation;

import com.astrayzjt.faultpilot.common.domain.CauseCode;
import com.astrayzjt.faultpilot.common.domain.EvidenceType;

import java.util.List;

public record EvaluationCaseDefinition(String caseCode, CauseCode expectedCause, List<EvidenceType> expectedEvidence,
                                       List<String> expectedAgents) {
}
