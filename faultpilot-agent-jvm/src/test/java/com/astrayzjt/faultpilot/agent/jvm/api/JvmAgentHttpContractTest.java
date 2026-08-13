package com.astrayzjt.faultpilot.agent.jvm.api;

import com.astrayzjt.faultpilot.agent.jvm.config.JvmAgentProperties;
import com.astrayzjt.faultpilot.agent.jvm.protocol.A2aTaskSnapshot;
import com.astrayzjt.faultpilot.agent.jvm.protocol.DelegationRequest;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TaskStatus;
import com.astrayzjt.faultpilot.agent.jvm.protocol.TimeRange;
import com.astrayzjt.faultpilot.agent.jvm.security.JvmAgentSecurityConfiguration;
import com.astrayzjt.faultpilot.agent.jvm.task.JvmTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {JvmAgentCardController.class, JvmTaskController.class}, properties = {
        "faultpilot.jvm-agent.a2a-token=secret",
        "faultpilot.jvm-agent.public-url=http://localhost:8091/a2a",
        "faultpilot.jvm-agent.capability-version=1.0.0"
})
@Import({JvmAgentSecurityConfiguration.class, JvmAgentExceptionHandler.class})
@EnableConfigurationProperties(JvmAgentProperties.class)
class JvmAgentHttpContractTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JvmTaskService tasks;

    private DelegationRequest request;
    private A2aTaskSnapshot snapshot;

    @BeforeEach
    void setUp() {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        request = new DelegationRequest(DelegationRequest.SCHEMA_VERSION, taskId, "run:1:jvm:hash", "1.0.0",
                new DelegationRequest.IncidentContext(UUID.randomUUID(), UUID.randomUUID(), "order-service",
                        "CPU high", new TimeRange(now.minusSeconds(60), now)), "Find CPU hotspot", List.of(),
                new DelegationRequest.Limits(4, now.plusSeconds(60)));
        snapshot = new A2aTaskSnapshot(A2aTaskSnapshot.SCHEMA_VERSION, UUID.randomUUID().toString(), taskId,
                TaskStatus.SUBMITTED, null, null, null, now);
    }

    @Test
    void exposesAgentCardWithoutAuthentication() throws Exception {
        mvc.perform(get("/.well-known/agent-card.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value("jvm-agent"))
                .andExpect(jsonPath("$.agentType").value("JVM_AGENT"))
                .andExpect(jsonPath("$.capabilityVersion").value("1.0.0"))
                .andExpect(jsonPath("$.url").value("http://localhost:8091/a2a"))
                .andExpect(jsonPath("$.supportsCancel").value(true));
    }

    @Test
    void requiresBearerTokenThenSubmitsQueriesAndCancelsA2aTask() throws Exception {
        when(tasks.submit(any())).thenReturn(snapshot);
        when(tasks.query(UUID.fromString(snapshot.remoteTaskId()))).thenReturn(Optional.of(snapshot));
        when(tasks.cancel(UUID.fromString(snapshot.remoteTaskId()))).thenReturn(true);

        mvc.perform(post("/a2a/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isForbidden());

        mvc.perform(post("/a2a/tasks").header("Authorization", "Bearer secret")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/a2a/tasks/" + snapshot.remoteTaskId()))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(get("/a2a/tasks/{id}", snapshot.remoteTaskId())
                        .header("Authorization", "Bearer secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(request.taskId().toString()));

        mvc.perform(delete("/a2a/tasks/{id}", snapshot.remoteTaskId())
                        .header("Authorization", "Bearer secret"))
                .andExpect(status().isNoContent());

        verify(tasks).submit(request);
    }
}
