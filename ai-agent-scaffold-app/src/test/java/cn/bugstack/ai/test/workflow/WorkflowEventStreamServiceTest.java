package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.run.adapter.repository.IChatRunRepository;
import cn.bugstack.ai.domain.run.model.ChatRunEntity;
import cn.bugstack.ai.domain.workflow.adapter.repository.IIntelligentWorkflowRunRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.types.exception.AppException;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 历史回放和实时流必须在交付终态事件后正常完成。 */
public class WorkflowEventStreamServiceTest {

    @Test
    public void shouldReturnGenericWorkflowRunWithRootTrace() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        WorkflowEventStreamService service = new WorkflowEventStreamService(events, runs, workflowChatRuns());

        ChatRunEntity run = service.requireWorkflowRun("tenant_1", "user_1", "run_1");

        Assert.assertEquals("workflow", run.getSourceType());
        Assert.assertEquals("trace_root", run.getTraceId());
    }

    @Test
    public void shouldAcceptGenericAgentRunForUnifiedExecutionEvents() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        IChatRunRepository chatRuns = mock(IChatRunRepository.class);
        when(chatRuns.query("tenant_1", "user_1", "run_1")).thenReturn(ChatRunEntity.builder()
                .tenantId("tenant_1").userId("user_1").runId("run_1")
                .sourceType("agent").traceId("trace_root").build());

        ChatRunEntity run = new WorkflowEventStreamService(events, runs, chatRuns)
                .requireWorkflowRun("tenant_1", "user_1", "run_1");

        Assert.assertEquals("agent", run.getSourceType());
        Assert.assertEquals("trace_root", run.getTraceId());
    }

    @Test
    public void shouldCompleteAfterReplayedTerminalEvent() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        IChatRunRepository chatRuns = workflowChatRuns();
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(1L);
        when(events.queryAfter("tenant_1", "user_1", "run_1", 0L, 1000)).thenReturn(List.of(
                event(1L, "WORKFLOW_STARTED"),
                event(2L, "WORKFLOW_COMPLETED")));

        TestSubscriber<WorkflowRunEventEntity> subscriber = new WorkflowEventStreamService(events, runs, chatRuns)
                .stream("tenant_1", "user_1", "run_1", 0L)
                .test();

        subscriber.assertComplete().assertNoErrors().assertValueCount(2);
        subscriber.assertValueAt(1, event -> "WORKFLOW_COMPLETED".equals(event.getEventType()));
    }

    @Test
    public void shouldReleaseStreamAfterWaitAllBoundary() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(1L);
        when(events.queryAfter("tenant_1", "user_1", "run_1", 0L, 1000)).thenReturn(List.of(
                event(1L, "AGENT_STARTED"), event(2L, "WAITING_ALL"), event(3L, "THINKING_DELTA")));

        TestSubscriber<WorkflowRunEventEntity> subscriber = new WorkflowEventStreamService(
                events, mock(IIntelligentWorkflowRunRepository.class), workflowChatRuns())
                .stream("tenant_1", "user_1", "run_1", 0L).test();

        subscriber.assertComplete().assertNoErrors().assertValueCount(2);
        subscriber.assertValueAt(1, value -> "WAITING_ALL".equals(value.getEventType()));
    }

    @Test
    public void shouldReplayPastWaitAllWhenRunIsAlreadyTerminal() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(1L);
        when(events.queryTerminal("tenant_1", "user_1", "run_1")).thenReturn(event(4L, "WORKFLOW_COMPLETED"));
        when(events.queryAfter("tenant_1", "user_1", "run_1", 0L, 1000)).thenReturn(List.of(
                event(1L, "AGENT_STARTED"), event(2L, "WAITING_ALL"),
                event(3L, "ANSWER_DELTA"), event(4L, "WORKFLOW_COMPLETED")));

        new WorkflowEventStreamService(events, mock(IIntelligentWorkflowRunRepository.class), workflowChatRuns())
                .stream("tenant_1", "user_1", "run_1", 0L).test()
                .assertComplete().assertNoErrors().assertValueCount(4);
    }

    @Test
    public void shouldCompleteAfterLiveTerminalEvent() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        IChatRunRepository chatRuns = workflowChatRuns();
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(null);
        List<WorkflowRunEventEntity> storedEvents = new CopyOnWriteArrayList<>();
        when(events.queryAfter(org.mockito.ArgumentMatchers.eq("tenant_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("run_1"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(1000)))
                .thenAnswer(invocation -> queryAfter(storedEvents, invocation.getArgument(3)));
        AtomicLong sequence = new AtomicLong();
        when(events.append(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            WorkflowRunEventEntity stored = invocation.getArgument(0);
            stored.setSequence(sequence.incrementAndGet());
            storedEvents.add(stored);
            return stored;
        });
        WorkflowEventStreamService service = new WorkflowEventStreamService(events, runs, chatRuns);
        TestSubscriber<WorkflowRunEventEntity> subscriber = service
                .stream("tenant_1", "user_1", "run_1", 0L)
                .test();

        service.publish("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED", null, "node_1", "{}");
        service.publish("tenant_1", "user_1", "run_1", "trace_root", "WORKFLOW_CANCELLED", null, null, "{}");

        subscriber.assertComplete().assertNoErrors().assertValueCount(2);
        subscriber.assertValueAt(1, event -> "WORKFLOW_CANCELLED".equals(event.getEventType()));
    }

    @Test
    public void shouldObserveConcurrentPublishesInAllocatedSequenceOrder() throws Exception {
        int eventCount = 32;
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        IChatRunRepository chatRuns = workflowChatRuns();
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(null);
        List<WorkflowRunEventEntity> storedEvents = new CopyOnWriteArrayList<>();
        when(events.queryAfter(org.mockito.ArgumentMatchers.eq("tenant_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("run_1"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(1000)))
                .thenAnswer(invocation -> queryAfter(storedEvents, invocation.getArgument(3)));
        AtomicLong sequence = new AtomicLong();
        when(events.append(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            WorkflowRunEventEntity stored = invocation.getArgument(0);
            stored.setSequence(sequence.incrementAndGet());
            storedEvents.add(stored);
            return stored;
        });
        WorkflowEventStreamService service = new WorkflowEventStreamService(events, runs, chatRuns);
        TestSubscriber<WorkflowRunEventEntity> subscriber = service
                .stream("tenant_1", "user_1", "run_1", 0L).test();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(eventCount);
        try {
            for (int index = 0; index < eventCount; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        service.publish("tenant_1", "user_1", "run_1", "trace_root",
                                "NODE_OUTPUT_DELTA", "node_exec", "node_1", "{}");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Assert.assertTrue(done.await(10, TimeUnit.SECONDS));
            subscriber.awaitCount(eventCount);
            List<Long> actual = subscriber.values().stream().map(WorkflowRunEventEntity::getSequence).toList();
            List<Long> expected = new ArrayList<>();
            for (long value = 1; value <= eventCount; value++) expected.add(value);
            Assert.assertEquals(expected, actual);
        } finally {
            subscriber.cancel();
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldContinueDatabaseTailBeyondFirstThousandEvents() {
        IWorkflowEventRepository events = mock(IWorkflowEventRepository.class);
        IIntelligentWorkflowRunRepository runs = mock(IIntelligentWorkflowRunRepository.class);
        List<WorkflowRunEventEntity> storedEvents = new ArrayList<>();
        for (long sequence = 1; sequence <= 1000; sequence++) {
            storedEvents.add(event(sequence, sequence == 1 ? "WORKFLOW_STARTED" : "NODE_OUTPUT_DELTA"));
        }
        storedEvents.add(event(1001L, "WORKFLOW_COMPLETED"));
        when(events.queryOldestSequence("tenant_1", "user_1", "run_1")).thenReturn(1L);
        when(events.queryAfter(org.mockito.ArgumentMatchers.eq("tenant_1"),
                org.mockito.ArgumentMatchers.eq("user_1"), org.mockito.ArgumentMatchers.eq("run_1"),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(1000)))
                .thenAnswer(invocation -> queryAfter(storedEvents, invocation.getArgument(3)));

        TestSubscriber<WorkflowRunEventEntity> subscriber = new WorkflowEventStreamService(events, runs, workflowChatRuns())
                .stream("tenant_1", "user_1", "run_1", 0L).test();

        subscriber.awaitDone(3, TimeUnit.SECONDS).assertComplete().assertNoErrors().assertValueCount(1001);
        subscriber.assertValueAt(1000, value -> "WORKFLOW_COMPLETED".equals(value.getEventType()));
    }

    private WorkflowRunEventEntity event(long sequence, String type) {
        return WorkflowRunEventEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .eventId("event_" + sequence).sequence(sequence).schemaVersion("workflow-event-v1")
                .eventType(type).payloadJson("{}").traceId("trace_root").build();
    }

    private List<WorkflowRunEventEntity> queryAfter(List<WorkflowRunEventEntity> events, long afterSequence) {
        return events.stream().filter(event -> event.getSequence() > afterSequence)
                .sorted(Comparator.comparingLong(WorkflowRunEventEntity::getSequence))
                .limit(1000).toList();
    }

    private IChatRunRepository workflowChatRuns() {
        IChatRunRepository chatRuns = mock(IChatRunRepository.class);
        when(chatRuns.query("tenant_1", "user_1", "run_1")).thenReturn(ChatRunEntity.builder()
                .tenantId("tenant_1").userId("user_1").runId("run_1")
                .sourceType("workflow").traceId("trace_root").build());
        return chatRuns;
    }
}
