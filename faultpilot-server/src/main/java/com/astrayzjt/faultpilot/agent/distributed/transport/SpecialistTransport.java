package com.astrayzjt.faultpilot.agent.distributed.transport;

import com.astrayzjt.faultpilot.agent.distributed.domain.AgentDelegation;
import com.astrayzjt.faultpilot.agent.distributed.protocol.EvidenceReferenceArtifact;
import com.astrayzjt.faultpilot.common.domain.Evidence;
import com.astrayzjt.faultpilot.common.domain.IncidentSnapshot;

import java.time.Instant;
import java.util.List;

public interface SpecialistTransport {

    EvidenceReferenceArtifact execute(AgentDelegation delegation, IncidentSnapshot incident,
                                      List<Evidence> activeEvidence, Instant deadline);
}
