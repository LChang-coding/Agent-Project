package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.run.model.RunStatus;
import cn.bugstack.ai.domain.run.service.RunControlService;
import cn.bugstack.ai.domain.session.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.domain.workflow.service.WorkflowRunFinalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工作流最终消息与唯一终态事件的领域协调测试。 */
public class WorkflowRunFinalizationServiceTest {

    @Test
    public void shouldPersistAnswerBeforeCompletedTerminalEvents() {
        RunControlService runs = mock(RunControlService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        ChatRunEntity running = run(RunStatus.RUNNING);
        ChatRunEntity completed = run(RunStatus.COMPLETED);
        ChatMessageEntity message = ChatMessageEntity.builder().messageId("message_1").build();
        when(runs.completeWithAssistantMessage("tenant_1", "user_1", "run_1", "最终答案", "trace_root", "{}"))
                .thenReturn(message);
        when(runs.require("tenant_1", "user_1", "run_1")).thenReturn(completed);

        new WorkflowRunFinalizationService(runs, events, new ObjectMapper())
                .complete(running, "最终答案", "{}", 4);

        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root"),
                eq("FINAL_ANSWER_DELTA"), eq(null), eq(null), anyString());
        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root"),
                eq("FINAL_ANSWER_COMPLETED"), eq(null), eq(null), anyString());
        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root"),
                eq("WORKFLOW_COMPLETED"), eq(null), eq(null), anyString());
    }

    @Test
    public void shouldPublishCancellationWhenCancelWonCompletionRace() {
        RunControlService runs = mock(RunControlService.class);
        WorkflowEventStreamService events = mock(WorkflowEventStreamService.class);
        ChatRunEntity running = run(RunStatus.RUNNING);
        ChatRunEntity cancelled = run(RunStatus.CANCELLED);
        cancelled.setTerminalReason("用户取消");
        when(runs.require("tenant_1", "user_1", "run_1")).thenReturn(cancelled);

        new WorkflowRunFinalizationService(runs, events, new ObjectMapper())
                .complete(running, "迟到答案", "{}", 2);

        verify(events).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root"),
                eq("WORKFLOW_CANCELLED"), eq(null), eq(null), anyString());
        verify(events, never()).publish(eq("tenant_1"), eq("user_1"), eq("run_1"), eq("trace_root"),
                eq("WORKFLOW_COMPLETED"), eq(null), eq(null), anyString());
    }

    private ChatRunEntity run(RunStatus status) {
        return ChatRunEntity.builder().tenantId("tenant_1").userId("user_1").sessionId("session_1")
                .runId("run_1").sourceType("workflow").sourceId("workflow_1")
                .traceId("trace_root").status(status).build();
    }
}
