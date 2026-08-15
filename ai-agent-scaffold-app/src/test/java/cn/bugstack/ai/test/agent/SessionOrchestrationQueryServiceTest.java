package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IToolApprovalRepository;
import cn.bugstack.ai.domain.agent.model.entity.SessionOrchestrationSnapshotEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.entity.ToolApprovalRequestEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
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
        IParentResumeRepository resumes = Mockito.mock(IParentResumeRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(task(SubagentTaskStatus.RUNNING, null)));
        Mockito.when(resumes.queryStatus("tenant", "run")).thenReturn("WAITING", "WAITING", "COMPLETED");
        Mockito.when(runs.queryExecutableBySession("tenant", "user", "session")).thenReturn(List.of());
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals, resumes, runs);

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
        IParentResumeRepository resumes = Mockito.mock(IParentResumeRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(task(SubagentTaskStatus.CANCELLED, "PENDING")));
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of(
                ToolApprovalRequestEntity.builder().approvalId("approval").parentRunId("run").parentAgentId("root")
                        .toolCode("create_subagent_instances").revision(0L).expiresAt(LocalDateTime.now().plusMinutes(1)).build()));
        Mockito.when(resumes.queryStatus("tenant", "run")).thenReturn("WAITING", "WAITING", "COMPLETED");
        Mockito.when(runs.queryExecutableBySession("tenant", "user", "session")).thenReturn(List.of());
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals, resumes, runs);
        Assert.assertEquals("WAITING_APPROVAL", service.query("tenant", "user", "session").getPhase());

        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        SessionOrchestrationSnapshotEntity stillWaiting = service.query("tenant", "user", "session");
        Assert.assertEquals("SUMMARIZING", stillWaiting.getPhase());
        Assert.assertTrue(stillWaiting.isInputLocked());

        SessionOrchestrationSnapshotEntity cancelled = service.query("tenant", "user", "session");
        Assert.assertEquals("CANCELLED", cancelled.getPhase());
        Assert.assertFalse(cancelled.isInputLocked());
    }

    @Test
    public void shouldLockActiveParentBeforeSubtasksExistAndRejectWaitAllMutation() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        IToolApprovalRepository approvals = Mockito.mock(IToolApprovalRepository.class);
        IParentResumeRepository resumes = Mockito.mock(IParentResumeRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        Mockito.when(runs.queryExecutableBySession("tenant", "user", "session")).thenReturn(List.of(
                ChatRunEntity.builder().runId("run").status(RunStatus.RUNNING).version(2).build()));
        Mockito.when(resumes.isAwaitingSummary("tenant", "run")).thenReturn(true);
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals, resumes, runs);

        SessionOrchestrationSnapshotEntity snapshot = service.query("tenant", "user", "session");
        Assert.assertTrue(snapshot.isInputLocked());
        Assert.assertEquals("EXECUTING", snapshot.getPhase());
        try { service.assertAcceptsRunMutation("tenant", "run"); Assert.fail("WAIT_ALL 父运行不可改写"); }
        catch (AppException exception) { Assert.assertEquals("SESSION_ORCHESTRATION_ACTIVE", exception.getCode()); }
    }

    @Test
    public void shouldKeepMixedCancelledBatchLockedUntilParentSummaryCompletes() {
        ISubagentTaskRepository tasks = Mockito.mock(ISubagentTaskRepository.class);
        IToolApprovalRepository approvals = Mockito.mock(IToolApprovalRepository.class);
        IParentResumeRepository resumes = Mockito.mock(IParentResumeRepository.class);
        IChatRunRepository runs = Mockito.mock(IChatRunRepository.class);
        Mockito.when(approvals.queryPendingBySession("tenant", "user", "session", 100)).thenReturn(List.of());
        Mockito.when(runs.queryExecutableBySession("tenant", "user", "session")).thenReturn(List.of());
        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(
                task(SubagentTaskStatus.SUCCEEDED, "REGISTERED"),
                task(SubagentTaskStatus.CANCELLED, "PENDING")));
        Mockito.when(resumes.queryStatus("tenant", "run")).thenReturn("WAITING", "COMPLETED");
        SessionOrchestrationQueryService service = new SessionOrchestrationQueryService(tasks, approvals, resumes, runs);

        SessionOrchestrationSnapshotEntity waiting = service.query("tenant", "user", "session");
        Assert.assertEquals("SUMMARIZING", waiting.getPhase());
        Assert.assertTrue(waiting.isInputLocked());

        Mockito.when(tasks.queryBySession("tenant", "user", "session", 100)).thenReturn(List.of(
                task(SubagentTaskStatus.ACKED, "DELIVERED"),
                task(SubagentTaskStatus.CANCELLED, "PENDING")));
        SessionOrchestrationSnapshotEntity completed = service.query("tenant", "user", "session");
        Assert.assertEquals("COMPLETED", completed.getPhase());
        Assert.assertFalse(completed.isInputLocked());
    }

    private SubagentTaskEntity task(SubagentTaskStatus status, String callbackStatus) {
        return SubagentTaskEntity.builder().tenantId("tenant").userId("user").parentSessionId("session")
                .parentRunId("run").parentAgentId("root").taskId("task").childAgentId("child")
                .instruction("do it").status(status).callbackStatus(callbackStatus).attempt(1)
                .createdAt(LocalDateTime.now().minusSeconds(2)).completedAt(status.terminal() ? LocalDateTime.now() : null).build();
    }
}
