package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.model.entity.IntelligentWorkflowRunEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 历史回放和实时流必须在交付终态事件后正常完成。 */
public class WorkflowEventStreamServiceTest {

    @Test
    public void shouldCompleteAfterReplayedTerminalEvent() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        when(runs.query("tenant_1", "user_1", "run_1")).thenReturn(IntelligentWorkflowRunEntity.builder()
                .tenantId("tenant_1").userId("user_1").runId("run_1").traceId("trace_root").status("COMPLETED").build());
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(1L);
        when(events.queryAfter("tenant_1", "user_1", "run_1", 0L, 1000)).thenReturn(List.of(
                event(1L, "WORKFLOW_STARTED"),
                event(2L, "WORKFLOW_COMPLETED")));

        TestSubscriber<WorkflowRunEventEntity> subscriber = new WorkflowEventStreamService(events, runs)
                .stream("tenant_1", "user_1", "run_1", 0L)
                .test();

        subscriber.assertComplete().assertNoErrors().assertValueCount(2);
        subscriber.assertValueAt(1, event -> "WORKFLOW_COMPLETED".equals(event.getEventType()));
    }

    @Test
    public void shouldCompleteAfterLiveTerminalEvent() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        when(runs.query("tenant_1", "user_1", "run_1")).thenReturn(IntelligentWorkflowRunEntity.builder()
                .tenantId("tenant_1").userId("user_1").runId("run_1").traceId("trace_root").status("RUNNING").build());
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(null);
        when(events.queryAfter("tenant_1", "user_1", "run_1", 0L, 1000)).thenReturn(List.of());
        AtomicLong sequence = new AtomicLong();
        when(events.append(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            WorkflowRunEventEntity stored = invocation.getArgument(0);
            stored.setSequence(sequence.incrementAndGet());
            return stored;
        });
        WorkflowEventStreamService service = new WorkflowEventStreamService(events, runs);
        TestSubscriber<WorkflowRunEventEntity> subscriber = service
                .stream("tenant_1", "user_1", "run_1", 0L)
                .test();

        service.publish("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED", null, "node_1", "{}");
        service.publish("tenant_1", "user_1", "run_1", "trace_root", "WORKFLOW_CANCELLED", null, null, "{}");

        subscriber.assertComplete().assertNoErrors().assertValueCount(2);
        subscriber.assertValueAt(1, event -> "WORKFLOW_CANCELLED".equals(event.getEventType()));
    }

    private WorkflowRunEventEntity event(long sequence, String type) {
        return WorkflowRunEventEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .eventId("event_" + sequence).sequence(sequence).schemaVersion("workflow-event-v1")
                .eventType(type).payloadJson("{}").traceId("trace_root").build();
    }
}
