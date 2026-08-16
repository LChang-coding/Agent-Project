package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.AgentToolPermissionEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.service.ToolApprovalService;
import cn.bugstack.ai.domain.tool.model.entity.ToolInvokeContextEntity;
import cn.bugstack.ai.domain.run.service.RunControlService;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

public class ToolApprovalServiceTest {
    @Test
    public void shouldPersistTrustedApprovalRequestAndApplySingleDecision() {
        IToolApprovalRepository repository = Mockito.mock(IToolApprovalRepository.class);
        Mockito.when(repository.createOrReplay(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(repository.decide(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(1);
        RunControlService runControlService = Mockito.mock(RunControlService.class);
        ToolApprovalService service = new ToolApprovalService(repository, runControlService);
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder().tenantId("tenant-1").userId("user-1")
                .agentId("parent-1").sessionId("session-1").runId("run-1").orchestrationRootRunId("root-1")
                .functionCallId("call-1").traceId("trace-1").orchestrationRole("SUPERVISOR")
                .allowedSubAgentIds(List.of("child-1")).build();
        AgentToolPermissionEntity policy = AgentToolPermissionEntity.builder().mode("REQUIRE_APPROVAL")
                .timeoutSeconds(60).timeoutDecision("REJECT").suggestions(List.of("同意", "拒绝")).build();

        ToolApprovalRequestEntity request = service.request(context, "create_subagent_instances",
                Map.of("tasks", List.of(Map.of("agentId", "child-1", "instruction", "research"))), policy);

        Assert.assertEquals("root-1", request.getParentRunId());
        Assert.assertEquals(List.of("child-1"), request.getAllowedSubAgentIds());
        Assert.assertEquals("PENDING", request.getStatus());
        service.decide("tenant-1", "user-1", request.getApprovalId(), "APPROVE_WITH_CHANGES",
                "减少范围", Map.of("tasks", List.of(Map.of("agentId", "child-1", "instruction", "brief"))), 0L);
        Mockito.verify(repository).decide(Mockito.eq("tenant-1"), Mockito.eq("user-1"),
                Mockito.eq(request.getApprovalId()), Mockito.eq("APPROVE_WITH_CHANGES"), Mockito.eq("减少范围"),
                Mockito.any(), Mockito.any(), Mockito.eq(0L), Mockito.any());
    }

    @Test
    public void shouldReturnApprovedDecisionToWaitingToolCall() {
        IToolApprovalRepository repository = Mockito.mock(IToolApprovalRepository.class);
        RunControlService runControlService = Mockito.mock(RunControlService.class);
        ToolApprovalService service = new ToolApprovalService(repository, runControlService);
        ToolApprovalRequestEntity request = ToolApprovalRequestEntity.builder().tenantId("tenant-1")
                .userId("user-1").approvalId("approval-1").status("PENDING").build();
        ToolApprovalRequestEntity decided = ToolApprovalRequestEntity.builder().tenantId("tenant-1")
                .userId("user-1").approvalId("approval-1").status("DECIDED").decision("APPROVE").build();
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder().tenantId("tenant-1").userId("user-1")
                .agentId("parent-1").sessionId("session-1").runId("run-1").functionCallId("call-1").build();
        Mockito.when(repository.query("tenant-1", "user-1", "approval-1")).thenReturn(decided);

        Assert.assertSame(decided, service.awaitDecision(request, context));

        Mockito.verify(runControlService).requireExecutable("tenant-1", "user-1", "run-1", null);
        Mockito.verify(runControlService).authorizeToolDispatch("tenant-1", "user-1", "run-1", null);
    }

    @Test
    public void shouldAcceptReplayOfSameApprovalDecision() {
        IToolApprovalRepository repository = Mockito.mock(IToolApprovalRepository.class);
        Mockito.when(repository.decide(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(0);
        Mockito.when(repository.query("tenant-1", "user-1", "approval-1")).thenReturn(
                ToolApprovalRequestEntity.builder().approvalId("approval-1").status("DECIDED")
                        .decision("APPROVE").build());
        ToolApprovalService service = new ToolApprovalService(repository, Mockito.mock(RunControlService.class));

        service.decide("tenant-1", "user-1", "approval-1", "APPROVE", "", null, 0L);
    }

    @Test
    public void shouldPersistTimeoutDecisionBeforeContinuingWaiter() {
        IToolApprovalRepository repository = Mockito.mock(IToolApprovalRepository.class);
        RunControlService runControlService = Mockito.mock(RunControlService.class);
        ToolApprovalService service = new ToolApprovalService(repository, runControlService);
        ToolApprovalRequestEntity expired = ToolApprovalRequestEntity.builder().tenantId("tenant-1")
                .userId("user-1").approvalId("approval-1").status("PENDING").revision(2L)
                .timeoutDecision("REJECT").expiresAt(java.time.LocalDateTime.now().minusSeconds(1)).build();
        ToolApprovalRequestEntity decided = ToolApprovalRequestEntity.builder().tenantId("tenant-1")
                .userId("user-1").approvalId("approval-1").status("DECIDED").decision("REJECT").build();
        ToolInvokeContextEntity context = ToolInvokeContextEntity.builder().tenantId("tenant-1").userId("user-1")
                .agentId("parent-1").sessionId("session-1").runId("run-1").functionCallId("call-1").build();
        Mockito.when(repository.query("tenant-1", "user-1", "approval-1"))
                .thenReturn(expired, decided);

        Assert.assertSame(decided, service.awaitDecision(expired, context));

        Mockito.verify(repository).decideTimeout(Mockito.eq("tenant-1"), Mockito.eq("approval-1"),
                Mockito.eq(2L), Mockito.eq("REJECT"), Mockito.any());
    }
}
