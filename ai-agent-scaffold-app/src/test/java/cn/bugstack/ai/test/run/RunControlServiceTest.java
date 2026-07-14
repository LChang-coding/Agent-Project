package cn.bugstack.ai.test.run;

import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行取消领域测试。
 */
public class RunControlServiceTest {

    @Test
    public void shouldInvalidateMessagesAndCompactionBeforeInterruptingRun() {
        IChatRunRepository runRepository = mock(IChatRunRepository.class);
        SessionDomain sessionDomain = mock(SessionDomain.class);
        ActiveRunRegistry activeRunRegistry = mock(ActiveRunRegistry.class);
        ContextInvalidationService contextInvalidationService = mock(ContextInvalidationService.class);
        RunControlService service = new RunControlService(runRepository, sessionDomain, activeRunRegistry,
                contextInvalidationService);
        ChatRunEntity running = ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.RUNNING).version(0).currentContextRevision(7L).build();
        ChatRunEntity cancelled = ChatRunEntity.builder()
                .runId("run-1").tenantId("tenant-1").userId("user-1").sessionId("session-1")
                .status(RunStatus.CANCELLED).version(3).currentContextRevision(8L).build();
        List<ChatMessageEntity> messages = List.of(ChatMessageEntity.builder()
                .messageId("message-1").sequenceNo(12).runId("run-1").build());
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
        when(runRepository.query("tenant-1", "user-1", "run-1")).thenReturn(cancelled);

        ChatRunEntity result = service.cancel("tenant-1", "user-1", "run-1", "用户取消");

        assertEquals(RunStatus.CANCELLED, result.getStatus());
        verify(sessionDomain).invalidateRunMessages("tenant-1", "user-1", "session-1", "run-1", "用户取消");
        verify(contextInvalidationService).invalidateRun("tenant-1", "user-1", "session-1", "run-1",
                messages, "用户取消");
        verify(activeRunRegistry).interrupt("run-1");
    }
}
