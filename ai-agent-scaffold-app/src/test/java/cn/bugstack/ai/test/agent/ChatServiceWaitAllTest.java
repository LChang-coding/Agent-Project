package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.service.ChatService;
import cn.bugstack.ai.domain.agent.service.ParentWaitAllFinalizationService;
import cn.bugstack.ai.domain.context.service.ConversationMemoryService;
import cn.bugstack.ai.domain.rag.service.RagAnswerCitationValidator;
import cn.bugstack.ai.domain.rag.service.RagInvocationEvidenceStore;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.exception.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ChatService 在 WAIT_ALL 期间隐藏草稿和流式正文的契约测试。 */
public class ChatServiceWaitAllTest {

    @Test
    public void shouldStoreParentOutputAsDraftInsteadOfVisibleAssistantMessage() {
        Fixture fixture = fixture();
        when(fixture.finalization.completeAsDraftIfWaiting("tenant-1", "user-1", "run-1", "主 Agent 草稿"))
                .thenReturn(true);

        Boolean deferred = ReflectionTestUtils.invokeMethod(fixture.service, "completeRunWithAssistant",
                "tenant-1", "user-1", "run-1", "主 Agent 草稿", "trace-1", List.of());

        assertTrue(Boolean.TRUE.equals(deferred));
        verify(fixture.runControlService, never()).completeWithAssistantMessage(
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(fixture.evidenceStore).clear("tenant-1", "user-1", "session-1", "run-1");
    }

    @Test
    public void shouldStoreParentFailureAsDraftInsteadOfVisibleErrorMessage() {
        Fixture fixture = fixture();
        IllegalStateException failure = new IllegalStateException("模型调用失败");
        String draft = "[assistant_error] type=IllegalStateException message=模型调用失败\npartialContent=已有片段";
        when(fixture.finalization.failAsDraftIfWaiting(
                "tenant-1", "user-1", "run-1", draft, "模型调用失败")).thenReturn(true);

        Boolean deferred = ReflectionTestUtils.invokeMethod(fixture.service, "failRunWithAssistantError",
                "tenant-1", "user-1", "run-1", "trace-1", failure, "已有片段");

        assertTrue(Boolean.TRUE.equals(deferred));
        verify(fixture.runControlService, never()).failWithAssistantMessage(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(fixture.evidenceStore).clear("tenant-1", "user-1", "session-1", "run-1");
    }

    @Test
    public void shouldHideSummaryRetryFailureInsteadOfWritingVisibleAssistantError() {
        Fixture fixture = fixture();
        IllegalStateException failure = new IllegalStateException("恢复模型首次调用失败");
        when(fixture.finalization.failAsDraftIfWaiting(
                eq("tenant-1"), eq("user-1"), eq("run-1"), anyString(), eq("恢复模型首次调用失败")))
                .thenReturn(false);
        when(fixture.runControlService.fail("tenant-1", "user-1", "run-1", "恢复模型首次调用失败"))
                .thenReturn(ChatRunEntity.builder().runId("run-1").status(RunStatus.FAILED).build());

        AgentOrchestrationContextHolder.setSummaryOnly(true);
        try {
            ReflectionTestUtils.invokeMethod(fixture.service, "failRunWithAssistantError",
                    "tenant-1", "user-1", "run-1", "trace-1", failure, "不应对用户可见的片段");
        } finally {
            AgentOrchestrationContextHolder.clear();
        }

        verify(fixture.runControlService).fail(
                "tenant-1", "user-1", "run-1", "恢复模型首次调用失败");
        verify(fixture.runControlService, never()).failWithAssistantMessage(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(fixture.evidenceStore).clear("tenant-1", "user-1", "session-1", "run-1");
    }

    @Test
    public void shouldRejectBlankSummaryBeforeCompletingStableRun() {
        Fixture fixture = fixture();
        AgentOrchestrationContextHolder.setSummaryOnly(true);
        try {
            ReflectionTestUtils.invokeMethod(fixture.service, "completeRunWithAssistant",
                    "tenant-1", "user-1", "run-1", "  ", "trace-1", List.of());
            fail("空恢复结果必须进入失败重试，不能把 stable run 标成完成");
        } catch (AppException exception) {
            assertTrue("PARENT_RESUME_EMPTY_OUTPUT".equals(exception.getCode()));
        } finally {
            AgentOrchestrationContextHolder.clear();
        }
        verify(fixture.runControlService, never()).completeWithAssistantMessage(
                anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void shouldSaveParentDraftWhenSseDisconnectsAfterDelegation() {
        Fixture fixture = fixture();
        AtomicBoolean saved = new AtomicBoolean(false);
        when(fixture.finalization.isAwaitingSummary("tenant-1", "run-1")).thenReturn(true);
        when(fixture.finalization.completeAsDraftIfWaiting(
                "tenant-1", "user-1", "run-1", "断开前草稿")).thenReturn(true);

        assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(fixture.service,
                "completeParentDraftOnDisconnectOnce", saved, "tenant-1", "user-1", "run-1", "断开前草稿")));

        verify(fixture.finalization).isAwaitingSummary("tenant-1", "run-1");
        verify(fixture.finalization).completeAsDraftIfWaiting(
                "tenant-1", "user-1", "run-1", "断开前草稿");
    }

    private Fixture fixture() {
        ChatService service = new ChatService();
        ParentWaitAllFinalizationService finalization = mock(ParentWaitAllFinalizationService.class);
        RunControlService runControlService = mock(RunControlService.class);
        RagInvocationEvidenceStore evidenceStore = mock(RagInvocationEvidenceStore.class);
        ChatRunEntity run = ChatRunEntity.builder().tenantId("tenant-1").userId("user-1")
                .sessionId("session-1").runId("run-1").build();
        when(runControlService.require("tenant-1", "user-1", "run-1")).thenReturn(run);
        ReflectionTestUtils.setField(service, "parentWaitAllFinalizationService", finalization);
        ReflectionTestUtils.setField(service, "runControlService", runControlService);
        ReflectionTestUtils.setField(service, "ragInvocationEvidenceStore", evidenceStore);
        ReflectionTestUtils.setField(service, "ragAnswerCitationValidator", mock(RagAnswerCitationValidator.class));
        ReflectionTestUtils.setField(service, "conversationMemoryService", mock(ConversationMemoryService.class));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        return new Fixture(service, finalization, runControlService, evidenceStore);
    }

    private record Fixture(ChatService service, ParentWaitAllFinalizationService finalization,
                           RunControlService runControlService, RagInvocationEvidenceStore evidenceStore) { }
}
