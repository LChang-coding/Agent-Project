package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.SessionOrchestrationSnapshotEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

/** 会话编排快照的阶段、锁定和租户用户隔离契约。 */
public class SessionOrchestrationQueryServiceTest {
    @Test
    public void shouldLockWhileExecutingAndUnlockAfterDeliveredCallback() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        IToolApprovalRepository approvals = Mockito.mock(IToolApprovalRepository.class);
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(task(SubagentTaskStatus.RUNNING, null)));
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals);

        SessionOrchestrationSnapshotEntity running = service.query("tenant", "user", "session");
        Assert.assertTrue(running.isInputLocked());
        Assert.assertEquals("EXECUTING", running.getPhase());
        try { service.assertAcceptsUserMessage("tenant", "user", "session"); Assert.fail("执行中必须锁定输入"); }
        catch (AppException exception) { Assert.assertEquals("SESSION_ORCHESTRATION_ACTIVE", exception.getCode()); }

        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(task(SubagentTaskStatus.ACKED, "DELIVERED")));
        SessionOrchestrationSnapshotEntity completed = service.query("tenant", "user", "session");
        Assert.assertFalse(completed.isInputLocked());
        Assert.assertEquals("COMPLETED", completed.getPhase());
    }

    @Test
    public void shouldPrioritizePendingApprovalAndCancelledTasksMustNotDeadlockInput() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        IToolApprovalRepository approvals = Mockito.mock(IToolApprovalRepository.class);
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(task(SubagentTaskStatus.CANCELLED, "PENDING")));
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of(
                ToolApprovalRequestEntity.builder().approvalId("approval").parentRunId("run").parentAgentId("root")
                        .toolCode("create_subagent_instances").revision(0L).expiresAt(LocalDateTime.now().plusMinutes(1)).build()));
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals);
        Assert.assertEquals("WAITING_APPROVAL", service.query("tenant", "user", "session").getPhase());

        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        SessionOrchestrationSnapshotEntity cancelled = service.query("tenant", "user", "session");
        Assert.assertEquals("CANCELLED", cancelled.getPhase());
        Assert.assertFalse(cancelled.isInputLocked());
    }

    private SubagentTaskEntity task(SubagentTaskStatus status, String callbackStatus) {
        return SubagentTaskEntity.builder().tenantId("tenant").userId("user").parentSessionId("session")
                .parentRunId("run").parentAgentId("root").taskId("task").childAgentId("child")
                .instruction("do it").status(status).callbackStatus(callbackStatus).attempt(1)
                .createdAt(LocalDateTime.now().minusSeconds(2)).completedAt(status.terminal() ? LocalDateTime.now() : null).build();
    }
}
