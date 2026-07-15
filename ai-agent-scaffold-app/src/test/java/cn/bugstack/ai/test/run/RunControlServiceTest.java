package cn.bugstack.ai.test.run;

import cn.bugstack.ai.domain.asset.service.AssetService;
import cn.bugstack.ai.domain.context.service.ContextInvalidationService;
import cn.bugstack.ai.domain.usage.service.ModelUsageService;
import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.ActiveRunRegistry;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.session.model.entity.ChatSessionEntity;
import cn.bugstack.ai.domain.session.service.SessionDomain;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import cn.bugstack.ai.types.exception.AppException;

/**
 * 运行取消领域测试。
 */
public class RunControlServiceTest {

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
        verify(runRepository).insert(argThat(run -> run.getStatus() == RunStatus.CREATED
                && "run-old".equals(run.getPredecessorRunId())));
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
}
