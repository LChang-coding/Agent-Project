package cn.bugstack.ai.test.session;

import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
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
import static org.mockito.Mockito.verify;
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
        SessionLifecycleService service = new SessionLifecycleService(sessionDomain, runControlService,
                contextInvalidationService, shareRepository);
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
    }
}
