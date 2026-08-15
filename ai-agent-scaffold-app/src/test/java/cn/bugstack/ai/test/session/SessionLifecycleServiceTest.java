package cn.bugstack.ai.test.session;

import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.agent.service.SessionOrchestrationQueryService;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
import cn.bugstack.ai.domain.share.adapter.ISessionShareRepository;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * 会话生命周期领域测试。
 */
public class SessionLifecycleServiceTest {

    /**
     * 删除会话前取消活动运行并清理上下文与分享；无参数；验证删除编排顺序所需操作。
     */
    @Test
    public void shouldCancelActiveRunsAndInvalidateDerivedStateBeforeSoftDelete() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService runControlService = mock(RunControlService.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        ISessionShareRepository shareRepository = mock(ISessionShareRepository.class);
        SessionOrchestrationQueryService orchestrationQueryService = mock(SessionOrchestrationQueryService.class);
        SessionLifecycleService service = new SessionLifecycleService(sessionDomain, runControlService,
                contextInvalidationService, shareRepository, orchestrationQueryService);
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").build();
        ChatRunEntity running = ChatRunEntity.builder().runId("run-1").tenantId("tenant-1")
                .userId("user-1").sessionId("session-1").status(RunStatus.RUNNING).build();
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(runControlService.queryExecutableBySession("tenant-1", "user-1", "session-1"))
                .thenReturn(List.of(running));
        when(runControlService.cancel("tenant-1", "user-1", "run-1", "会话已删除"))
                .thenReturn(ChatRunEntity.builder().runId("run-1").status(RunStatus.CANCELLED).build());
        when(sessionDomain.incrementContextRevision("tenant-1", "user-1", "session-1")).thenReturn(1L);
        when(sessionDomain.softDelete("tenant-1", "user-1", "session-1")).thenReturn(1);

        long revision = service.delete("tenant-1", "user-1", "session-1");

        assertEquals(1L, revision);
        verify(runControlService).cancel("tenant-1", "user-1", "run-1", "会话已删除");
        verify(contextInvalidationService).invalidateSession("tenant-1", "user-1", "session-1", "会话已删除");
        verify(shareRepository).revokeBySession("tenant-1", "user-1", "session-1");
        verify(sessionDomain).softDelete("tenant-1", "user-1", "session-1");
        verify(orchestrationQueryService, org.mockito.Mockito.times(2))
                .assertAcceptsSessionMutation("tenant-1", "user-1", "session-1");
    }

    @Test
    public void shouldRejectDeletionWhileWaitAllIsActive() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService runControlService = mock(RunControlService.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        ISessionShareRepository shareRepository = mock(ISessionShareRepository.class);
        SessionOrchestrationQueryService orchestrationQueryService = mock(SessionOrchestrationQueryService.class);
        SessionLifecycleService service = new SessionLifecycleService(sessionDomain, runControlService,
                contextInvalidationService, shareRepository, orchestrationQueryService);
        org.mockito.Mockito.doThrow(new cn.bugstack.ai.types.exception.AppException(
                        "SESSION_ORCHESTRATION_ACTIVE", "当前会话仍在执行 Multi-Agent 任务"))
                .when(orchestrationQueryService).assertAcceptsSessionMutation("tenant-1", "user-1", "session-1");

        try {
            service.delete("tenant-1", "user-1", "session-1");
            org.junit.Assert.fail("WAIT_ALL 期间必须拒绝删除会话");
        } catch (cn.bugstack.ai.types.exception.AppException exception) {
            assertEquals("SESSION_ORCHESTRATION_ACTIVE", exception.getCode());
        }

        verify(sessionDomain, never()).lockSessionAccess(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void shouldRecheckWaitAllAfterLockBeforeDeletingParentSession() {
        SessionDomain sessionDomain = mock(SessionDomain.class);
        RunControlService runControlService = mock(RunControlService.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        ISessionShareRepository shareRepository = mock(ISessionShareRepository.class);
        SessionOrchestrationQueryService orchestrationQueryService = mock(SessionOrchestrationQueryService.class);
        SessionLifecycleService service = new SessionLifecycleService(sessionDomain, runControlService,
                contextInvalidationService, shareRepository, orchestrationQueryService);
        ChatSessionEntity session = ChatSessionEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").build();
        when(sessionDomain.lockSessionAccess("tenant-1", "user-1", "session-1", null)).thenReturn(session);
        when(runControlService.queryExecutableBySession("tenant-1", "user-1", "session-1"))
                .thenReturn(List.of());
        when(sessionDomain.incrementContextRevision("tenant-1", "user-1", "session-1")).thenReturn(1L);
        when(sessionDomain.softDelete("tenant-1", "user-1", "session-1")).thenReturn(1);

        service.delete("tenant-1", "user-1", "session-1");

        org.mockito.InOrder order = inOrder(orchestrationQueryService, sessionDomain);
        order.verify(orchestrationQueryService)
                .assertAcceptsSessionMutation("tenant-1", "user-1", "session-1");
        order.verify(sessionDomain).lockSessionAccess("tenant-1", "user-1", "session-1", null);
        order.verify(orchestrationQueryService)
                .assertAcceptsSessionMutation("tenant-1", "user-1", "session-1");
    }
}
