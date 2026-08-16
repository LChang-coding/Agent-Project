package cn.bugstack.ai.test.run;

import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.rag.model.entity.SessionRagRunSnapshotEntity;
import cn.bugstack.ai.domain.rag.model.valobj.SessionRagMode;
import cn.bugstack.ai.domain.rag.model.valobj.RagInvocationMode;
import cn.bugstack.ai.domain.rag.service.SessionRagSettingService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

/**
 * 运行取消领域测试。
 */
public class RunControlServiceTest {

    @Test
    public void shouldCreateChildRunWithFrozenParentRagSnapshot() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ChatSessionEntity childSession = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("child-session-1").agentId("child-1").contextRevision(6L).build();
        ChatRunEntity parentRun = ChatRunEntity.builder().runId("parent-run-1")
                .tenantId("tenant-1").userId("user-1").sessionId("parent-session-1")
                .sourceType("agent").sourceId("parent-1").status(RunStatus.RUNNING)
                .ragEnabled(true).ragMode("MANUAL").ragInvocationMode("AGENT_TOOL")
                .ragPolicyRevision(9L).ragBindingIds(List.of("binding-1", "binding-2")).build();
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "child-session-1", "child-1"))
                .thenReturn(childSession);
        when(runRepository.query("tenant-1", "user-1", "parent-run-1")).thenReturn(parentRun);
        when(runRepository.insert(any())).thenReturn(1);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        ChatRunEntity childRun = service.startSubagentWithInheritedRag("tenant-1", "user-1",
                "child-session-1", "child-1", "parent-run-1", "parent-session-1", "parent-1");

        assertEquals("child-session-1", childRun.getSessionId());
        assertEquals("child-1", childRun.getSourceId());
        assertEquals(Boolean.TRUE, childRun.getRagEnabled());
        assertEquals("MANUAL", childRun.getRagMode());
        assertEquals("AGENT_TOOL", childRun.getRagInvocationMode());
        assertEquals(Long.valueOf(9L), childRun.getRagPolicyRevision());
        assertEquals(List.of("binding-1", "binding-2"), childRun.getRagBindingIds());
        assertEquals(Long.valueOf(6L), childRun.getCurrentContextRevision());
        ArgumentCaptor<ChatRunEntity> inserted = ArgumentCaptor.forClass(ChatRunEntity.class);
        verify(runRepository).insert(inserted.capture());
        assertEquals("child-1", inserted.getValue().getSourceId());
    }

    @Test
    public void shouldRejectChildRagInheritanceWhenParentScopeDoesNotMatch() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "child-session-1", "child-1"))
                .thenReturn(ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                        .sessionId("child-session-1").agentId("child-1").contextRevision(0L).build());
        when(runRepository.query("tenant-1", "user-1", "parent-run-1"))
                .thenReturn(ChatRunEntity.builder().runId("parent-run-1").tenantId("tenant-1")
                        .userId("user-1").sessionId("different-session").sourceType("agent")
                        .sourceId("parent-1").status(RunStatus.RUNNING).build());
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        try {
            service.startSubagentWithInheritedRag("tenant-1", "user-1", "child-session-1", "child-1",
                    "parent-run-1", "parent-session-1", "parent-1");
            fail("父运行范围不一致时不得继承RAG绑定");
        } catch (AppException exception) {
            assertEquals("SUBAGENT_PARENT_SCOPE_MISMATCH", exception.getCode());
        }
        verify(runRepository, never()).insert(any());
    }

    @Test
    public void shouldRecheckWaitAllAfterSessionLockBeforeStartingUserRun() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionOrchestrationQueryService orchestration = mock(SessionOrchestrationQueryService.class);
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").agentId("agent-1").contextRevision(0L).build();
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", "agent-1"))
                .thenReturn(session);
        org.mockito.Mockito.doThrow(new AppException(
                        "SESSION_ORCHESTRATION_ACTIVE", "当前会话仍在执行 Multi-Agent 任务"))
                .when(orchestration).assertAcceptsUserMessage("tenant-1", "user-1", "session-1");
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class), null, orchestration);

        try {
            service.start("tenant-1", "user-1", "session-1", "agent", "agent-1", null, null);
            fail("WAIT_ALL 建立后普通发送必须在会话锁内被拒绝");
        } catch (AppException exception) {
            assertEquals("SESSION_ORCHESTRATION_ACTIVE", exception.getCode());
        }

        org.mockito.InOrder order = inOrder(sessionDomain, orchestration);
        order.verify(sessionDomain).lockSessionAccess("tenant-1", "user-1", "session-1", "agent-1");
        order.verify(orchestration).assertAcceptsUserMessage("tenant-1", "user-1", "session-1");
        verify(runRepository, never()).insert(any());
    }

    @Test
    public void shouldBypassUserMessageGateForStableInternalResumeCreation() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionOrchestrationQueryService orchestration = mock(SessionOrchestrationQueryService.class);
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").agentId("agent-1").contextRevision(0L).ragEnabled(false).build();
        when(runRepository.query("tenant-1", "user-1", "run_resume_stable")).thenReturn(null);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", "agent-1"))
                .thenReturn(session);
        when(runRepository.insert(any())).thenReturn(1);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class), null, orchestration);

        ChatRunEntity run = service.startOrReuseInternal("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run_resume_stable");

        assertEquals("run_resume_stable", run.getRunId());
        verify(orchestration, never()).assertAcceptsUserMessage(any(), any(), any());
        verify(runRepository).insert(any());
    }

    @Test
    public void shouldRecheckWaitAllAfterParentRunLockBeforeCancelling() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionOrchestrationQueryService orchestration = mock(SessionOrchestrationQueryService.class);
        ChatRunEntity running = ChatRunEntity.builder().runId("run-1").tenantId("tenant-1")
                .userId("user-1").sessionId("session-1").status(RunStatus.RUNNING).version(0).build();
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(running);
        org.mockito.Mockito.doThrow(new AppException(
                        "SESSION_ORCHESTRATION_ACTIVE", "当前运行正在等待 Multi-Agent 统一汇总"))
                .when(orchestration).assertAcceptsRunMutation("tenant-1", "run-1");
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class), null, orchestration);

        try {
            service.cancel("tenant-1", "user-1", "run-1", "用户取消");
            fail("WAIT_ALL 建立后取消必须在父运行锁内被拒绝");
        } catch (AppException exception) {
            assertEquals("SESSION_ORCHESTRATION_ACTIVE", exception.getCode());
        }

        org.mockito.InOrder order = inOrder(sessionDomain, runRepository, orchestration);
        order.verify(sessionDomain).lockSessionAccess("tenant-1", "user-1", "session-1", null);
        order.verify(runRepository).lock("tenant-1", "user-1", "run-1");
        order.verify(orchestration).assertAcceptsRunMutation("tenant-1", "run-1");
        verify(runRepository, never()).transition(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }

    @Test
    public void shouldKeepWaitAllCancelGateInsideTransactionalRunService() {
        boolean hasTransactionalWaitAllGuard = Arrays.stream(RunControlService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .anyMatch(type -> SessionOrchestrationQueryService.class.isAssignableFrom(type)
                        || IParentResumeRepository.class.isAssignableFrom(type));

        assertTrue("取消与委派必须在 RunControlService 事务内共享 WAIT_ALL 权威闸门，"
                + "不能只依赖 Controller 的事前检查", hasTransactionalWaitAllGuard);
    }

    @Test
    public void shouldBindAttachmentsInsideUserMessageOperation() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        AssetService assetService = mock(AssetService.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                assetService);
        ChatRunEntity running = ChatRunEntity.builder().runId("run-1").tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").status(RunStatus.RUNNING).version(0).build();
        ChatMessageEntity message = ChatMessageEntity.builder().messageId("message-1").sessionId("session-1").build();
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(sessionDomain.appendUserMessage("tenant-1", "user-1", "session-1", "run-1", "正文", "trace-1"))
                .thenReturn(message);
        when(runRepository.bindUserMessage("tenant-1", "user-1", "run-1", "message-1", 0)).thenReturn(1);

        service.appendUserMessage("tenant-1", "user-1", "run-1", "正文", "trace-1", List.of("asset-1"));

        verify(assetService).bindToMessage("tenant-1", "user-1", "session-1", "message-1", List.of("asset-1"));
        verify(runRepository).bindUserMessage("tenant-1", "user-1", "run-1", "message-1", 0);
    }

    @Test
    public void shouldCreateRunWithClientPreallocatedId() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", "agent-1"))
                .thenReturn(ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                        .sessionId("session-1").contextRevision(4L).build());
        when(runRepository.insert(any())).thenReturn(1);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        ChatRunEntity run = service.startOrResume("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run_client-1");

        assertEquals("run_client-1", run.getRunId());
        assertEquals(RunStatus.RUNNING, run.getStatus());
        assertEquals(Long.valueOf(4L), run.getCurrentContextRevision());
        verify(runRepository).insert(argThat(item -> "run_client-1".equals(item.getRunId())));
    }

    @Test
    public void shouldReuseStableInternalRunWithoutCreatingAnotherRun() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ChatRunEntity completed = ChatRunEntity.builder().runId("run_resume_stable")
                .tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType("agent").sourceId("agent-1").status(RunStatus.COMPLETED).build();
        when(runRepository.query("tenant-1", "user-1", "run_resume_stable")).thenReturn(completed);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        ChatRunEntity result = service.startOrReuseInternal("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run_resume_stable");

        assertEquals(RunStatus.COMPLETED, result.getStatus());
        verify(runRepository, never()).insert(any());
        verify(sessionDomain, never()).lockSessionAccess(any(), any(), any(), any());
    }

    @Test
    public void shouldMakeFailedStableInternalRunExecutableForResumeRetry() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ChatRunEntity failed = ChatRunEntity.builder().runId("run_resume_stable")
                .tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType("agent").sourceId("agent-1").status(RunStatus.FAILED).version(2).build();
        ChatRunEntity running = ChatRunEntity.builder().runId("run_resume_stable")
                .tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType("agent").sourceId("agent-1").status(RunStatus.RUNNING).version(3).build();
        when(runRepository.query("tenant-1", "user-1", "run_resume_stable"))
                .thenReturn(failed, running, running);
        when(runRepository.lock("tenant-1", "user-1", "run_resume_stable")).thenReturn(failed);
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run_resume_stable"),
                eq(RunStatus.FAILED), eq(RunStatus.RUNNING), eq(2), eq(null), eq(null), eq(null)))
                .thenReturn(1);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        ChatRunEntity retry = service.startOrReuseInternal("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run_resume_stable");
        ChatRunEntity duplicateDelivery = service.startOrReuseInternal("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run_resume_stable");

        assertTrue("恢复模型首次失败后必须重新进入可执行态，不能重放错误消息后 ACK",
                retry.getStatus().executable());
        assertEquals("重投必须继续复用同一稳定 run", "run_resume_stable", duplicateDelivery.getRunId());
        assertEquals(RunStatus.RUNNING, duplicateDelivery.getStatus());
        verify(runRepository).transition(eq("tenant-1"), eq("user-1"), eq("run_resume_stable"),
                eq(RunStatus.FAILED), eq(RunStatus.RUNNING), eq(2), eq(null), eq(null), eq(null));
        verify(runRepository, never()).insert(any());
    }

    @Test
    public void shouldRejectStableInternalRunFromAnotherScope() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        ChatRunEntity existing = ChatRunEntity.builder().runId("run_resume_stable")
                .tenantId("tenant-1").userId("user-1").sessionId("other-session")
                .sourceType("agent").sourceId("agent-1").status(RunStatus.COMPLETED).build();
        when(runRepository.query("tenant-1", "user-1", "run_resume_stable")).thenReturn(existing);
        RunControlService service = new RunControlService(runRepository, mock(SessionDomain.class),
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));

        try {
            service.startOrReuseInternal("tenant-1", "user-1", "session-1",
                    "agent", "agent-1", "run_resume_stable");
            fail("稳定恢复运行不得跨会话复用");
        } catch (AppException exception) {
            assertEquals("RUN_SCOPE_MISMATCH", exception.getCode());
        }
    }

    @Test
    public void shouldFreezeResolvedRagModeBindingsAndRevisionWhenRunStarts() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        SessionRagSettingService ragSettingService = mock(SessionRagSettingService.class);
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").agentId("agent-1").sourceType("agent")
                .contextRevision(4L).ragEnabled(true).ragMode("MANUAL").ragRevision(9L).build();
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", "agent-1"))
                .thenReturn(session);
        when(ragSettingService.resolveRunSnapshot(session)).thenReturn(
                new SessionRagRunSnapshotEntity(SessionRagMode.MANUAL, RagInvocationMode.AGENT_TOOL, 9L,
                         List.of("binding-2", "binding-1")));
        when(runRepository.insert(any())).thenReturn(1);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class), ragSettingService);

        ChatRunEntity run = service.start("tenant-1", "user-1", "session-1",
                "agent", "agent-1", "run-rag", null);

        assertEquals(Boolean.TRUE, run.getRagEnabled());
        assertEquals("MANUAL", run.getRagMode());
        assertEquals("AGENT_TOOL", run.getRagInvocationMode());
        assertEquals(Long.valueOf(9L), run.getRagPolicyRevision());
        assertEquals(List.of("binding-2", "binding-1"), run.getRagBindingIds());
        verify(runRepository).insert(argThat(item -> "MANUAL".equals(item.getRagMode())
                && "AGENT_TOOL".equals(item.getRagInvocationMode())
                && item.getRagBindingIds().equals(List.of("binding-2", "binding-1"))));
    }

    @Test
    public void shouldInvalidateMessagesAndCompactionBeforeInterruptingRun() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ActiveRunRegistry activeRunRegistry = mock(ActiveRunRegistry.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        ModelUsageService modelUsageService = mock(ModelUsageService.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain, activeRunRegistry,
                contextInvalidationService, modelUsageService, mock(AssetService.class));
        ChatRunEntity running = ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.RUNNING).version(0).currentContextRevision(7L).build();
        ChatRunEntity cancelled = ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).currentContextRevision(8L).build();
        List<ChatMessageEntity> messages = List.of(ChatMessageEntity.builder()
                .messageId("message-1").sequenceNo(12).runId("run-1").build());
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(running, cancelled);
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run-1"), any(), any(),
                eq(0), eq("用户取消"), any(), eq(null))).thenReturn(1);
        when(sessionDomain.queryRunMessages("tenant-1", "user-1", "session-1", "run-1"))
                .thenReturn(messages);
        when(sessionDomain.incrementContextRevision("tenant-1", "user-1", "session-1")).thenReturn(8L);
        when(runRepository.updateContextRevision("tenant-1", "user-1", "run-1", 8L, 1)).thenReturn(1);
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run-1"),
                eq(RunStatus.CANCEL_REQUESTED), eq(RunStatus.CANCELLED), eq(2), eq("用户取消"), any(), any()))
                .thenReturn(1);

        ChatRunEntity result = service.cancel("tenant-1", "user-1", "run-1", "用户取消");

        assertEquals(RunStatus.CANCELLED, result.getStatus());
        verify(sessionDomain).invalidateRunMessages("tenant-1", "user-1", "session-1", "run-1", "用户取消");
        verify(contextInvalidationService).invalidateRun("tenant-1", "user-1", "session-1", "run-1",
                messages, "用户取消");
        verify(modelUsageService).cancelRunning("tenant-1", "user-1", "session-1", "run-1", "用户取消");
        verify(activeRunRegistry).interrupt("run-1");
    }

    @Test
    public void shouldSupersedeOldRunAndCreatePreparedSuccessorWhenSteering() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ActiveRunRegistry activeRunRegistry = mock(ActiveRunRegistry.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain, activeRunRegistry,
                contextInvalidationService, mock(ModelUsageService.class), mock(AssetService.class));
        ChatRunEntity running = ChatRunEntity.builder()
                .runId("run-old").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .sourceType("agent").sourceId("agent-1")
                .ragEnabled(true).ragMode("MANUAL").ragInvocationMode("AGENT_TOOL").ragPolicyRevision(7L)
                .ragBindingIds(List.of("binding-2", "binding-1"))
                .status(RunStatus.RUNNING).version(0).currentContextRevision(2L).build();
        List<ChatMessageEntity> messages = List.of(ChatMessageEntity.builder()
                .messageId("message-old").role("user").sequenceNo(3).runId("run-old").build());
        when(runRepository.query("tenant-1", "user-1", "run-old")).thenReturn(running);
        when(runRepository.lock("tenant-1", "user-1", "run-old")).thenReturn(running);
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run-old"), eq(RunStatus.RUNNING),
                eq(RunStatus.STEER_REQUESTED), eq(0), any(), any(), eq(null))).thenReturn(1);
        when(sessionDomain.queryRunMessages("tenant-1", "user-1", "session-1", "run-old"))
                .thenReturn(messages);
        when(sessionDomain.incrementContextRevision("tenant-1", "user-1", "session-1")).thenReturn(3L);
        when(runRepository.updateContextRevision("tenant-1", "user-1", "run-old", 3L, 1)).thenReturn(1);
        when(runRepository.insert(any(ChatRunEntity.class))).thenReturn(1);
        when(runRepository.bindSuccessor(eq("tenant-1"), eq("user-1"), eq("run-old"), any(),
                eq("请改为简短答复"), eq(2))).thenReturn(1);
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run-old"),
                eq(RunStatus.STEER_REQUESTED), eq(RunStatus.SUPERSEDED), eq(3), any(), any(), any()))
                .thenReturn(1);

        ChatRunEntity successor = service.steer("tenant-1", "user-1", "run-old", "  请改为简短答复  ");

        assertEquals(RunStatus.CREATED, successor.getStatus());
        assertEquals("run-old", successor.getPredecessorRunId());
        assertEquals("请改为简短答复", successor.getSteerInstruction());
        assertEquals(Boolean.TRUE, successor.getRagEnabled());
        assertEquals("MANUAL", successor.getRagMode());
        assertEquals("AGENT_TOOL", successor.getRagInvocationMode());
        assertEquals(Long.valueOf(7L), successor.getRagPolicyRevision());
        assertEquals(List.of("binding-2", "binding-1"), successor.getRagBindingIds());
        verify(runRepository).insert(argThat(run -> run.getStatus() == RunStatus.CREATED
                && "run-old".equals(run.getPredecessorRunId())
                && "MANUAL".equals(run.getRagMode())
                && "AGENT_TOOL".equals(run.getRagInvocationMode())
                && List.of("binding-2", "binding-1").equals(run.getRagBindingIds())));
        verify(sessionDomain).invalidateRunMessages("tenant-1", "user-1", "session-1", "run-old", "用户引导替代");
        verify(contextInvalidationService).invalidateRun("tenant-1", "user-1", "session-1", "run-old",
                messages, "用户引导替代");
        verify(activeRunRegistry).interrupt("run-old");
    }

    @Test
    public void shouldRejectLateUserMessageAfterCancellationWonRunLock() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).build());
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).build());

        try {
            service.appendUserMessage("tenant-1", "user-1", "run-1", "晚到消息", "trace-1");
            fail("取消后的消息必须被拒绝");
        } catch (AppException e) {
            assertEquals("RUN_NOT_EXECUTABLE", e.getCode());
        }
        verify(sessionDomain, never()).appendUserMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void shouldNotPersistLateAssistantMessageAfterCancellationWonRunLock() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).build());
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).build());

        assertNull(service.completeWithAssistantMessage("tenant-1", "user-1", "run-1", "晚到回复", "trace-1"));
        verify(sessionDomain, never()).appendAssistantMessage(any(), any(), any(), any(), any(), any());
    }

    @Test
    public void shouldPersistCitationMetadataBeforeCompletingRunInSameOperation() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain,
                mock(ActiveRunRegistry.class), mock(ContextInvalidationService.class), mock(ModelUsageService.class),
                mock(AssetService.class));
        ChatRunEntity running = ChatRunEntity.builder().runId("run-1").tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").status(RunStatus.RUNNING).version(0).build();
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(runRepository.lock("tenant-1", "user-1", "run-1")).thenReturn(running);
        when(sessionDomain.appendAssistantMessage("tenant-1", "user-1", "session-1", "run-1",
                "回答", "trace-1", "{\"schema\":\"rag-citations/v1\"}"))
                .thenReturn(ChatMessageEntity.builder().messageId("msg-1").build());
        when(runRepository.transition(eq("tenant-1"), eq("user-1"), eq("run-1"), eq(RunStatus.RUNNING),
                eq(RunStatus.COMPLETED), eq(0), eq(null), eq(null), any())).thenReturn(1);

        ChatMessageEntity result = service.completeWithAssistantMessage("tenant-1", "user-1", "run-1",
                "回答", "trace-1", "{\"schema\":\"rag-citations/v1\"}");

        assertEquals("msg-1", result.getMessageId());
        verify(sessionDomain).appendAssistantMessage("tenant-1", "user-1", "session-1", "run-1",
                "回答", "trace-1", "{\"schema\":\"rag-citations/v1\"}");
    }
}
