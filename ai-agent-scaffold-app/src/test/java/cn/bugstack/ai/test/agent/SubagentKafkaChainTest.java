package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.infrastructure.agent.AgentOrchestrationOutboxPublisher;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.trigger.listener.SubagentResultCallbackConsumer;
import cn.bugstack.ai.trigger.listener.SubagentTaskConsumer;
import cn.bugstack.ai.trigger.listener.ParentAgentResumeConsumer;
import cn.bugstack.ai.trigger.listener.SubagentInstanceCleanupConsumer;
import cn.bugstack.ai.domain.session.service.SessionLifecycleService;
import cn.bugstack.ai.domain.workflow.service.WorkflowEventStreamService;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.context.TenantContextHolder;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import cn.bugstack.ai.types.observability.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SubagentKafkaChainTest {

    @After
    public void clearContext() {
        AgentOrchestrationContextHolder.clear();
        TenantContextHolder.clear();
        TraceContext.clear();
    }

    @Test
    public void shouldExecuteClaimedTaskAndCompleteWithFence() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.RUNNING);
        Mockito.when(repository.claim(Mockito.eq("tenant-1"), Mockito.eq("task-1"), Mockito.anyString(),
                Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)))).thenReturn(task);
        Mockito.when(chatService.createSubagentSession("child-1", "user-1")).thenReturn("child-session-1");
        Mockito.when(repository.bindChildSession(Mockito.eq("tenant-1"), Mockito.eq("task-1"),
                Mockito.anyString(), Mockito.eq(7L), Mockito.eq("child-session-1"))).thenReturn(1);
        Mockito.when(chatService.handleMessage("child-1", "user-1", "child-session-1", "research it"))
                .thenReturn(List.of("partial", "final answer"));
        Mockito.when(repository.complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L))).thenReturn(1);
        Mockito.doThrow(new IllegalStateException("redis down")).when(cache)
                .putInstance(Mockito.same(task), Mockito.eq(Duration.ofHours(2)));

        new SubagentTaskConsumer(new ObjectMapper(), repository, cache, chatService).consume(event());

        Assert.assertEquals(SubagentTaskStatus.SUCCEEDED, task.getStatus());
        Assert.assertEquals("final answer", task.getResultText());
        Assert.assertEquals("final answer", task.getResultSummary());
        Assert.assertEquals("final answer", task.getFullContext());
        Assert.assertFalse(task.getSummaryTruncated());
        Assert.assertEquals("child-session-1", task.getChildSessionId());
        Mockito.verify(cache).putInstance(Mockito.same(task), Mockito.eq(Duration.ofHours(2)));
        Mockito.verify(repository).complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L));
        Assert.assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    public void shouldBoundSummaryButKeepCompleteChildOutput() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.RUNNING);
        String longResult = "x".repeat(1200);
        task.setChildSessionId("child-session-1");
        Mockito.when(repository.claim(Mockito.eq("tenant-1"), Mockito.eq("task-1"), Mockito.anyString(),
                Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)))).thenReturn(task);
        Mockito.when(chatService.handleMessage("child-1", "user-1", "child-session-1", "research it"))
                .thenReturn(List.of(longResult));
        Mockito.when(repository.complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L))).thenReturn(1);

        new SubagentTaskConsumer(new ObjectMapper(), repository, cache, chatService).consume(event());

        Assert.assertEquals(1000, task.getResultSummary().length());
        Assert.assertEquals(longResult, task.getFullContext());
        Assert.assertTrue(task.getSummaryTruncated());
    }

    @Test
    public void shouldCompleteFailedTaskWhenChildSessionCannotBeCreated() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.RUNNING);
        Mockito.when(repository.claim(Mockito.eq("tenant-1"), Mockito.eq("task-1"), Mockito.anyString(),
                Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)))).thenReturn(task);
        Mockito.when(chatService.createSubagentSession("child-1", "user-1"))
                .thenThrow(new IllegalStateException("agent runtime unavailable"));
        Mockito.when(repository.complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L))).thenReturn(1);

        new SubagentTaskConsumer(new ObjectMapper(), repository, cache, chatService).consume(event());

        Assert.assertEquals(SubagentTaskStatus.FAILED, task.getStatus());
        Assert.assertEquals("IllegalStateException", task.getErrorCode());
        Assert.assertNotNull(task.getCompletedAt());
        Mockito.verify(repository).complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L));
        Mockito.verify(chatService, Mockito.never()).handleMessage(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString());
        Assert.assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    public void shouldRegisterResumeWithoutRunningParentInKafkaResultConsumer() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.SUCCEEDED);
        Mockito.when(repository.queryByIds("tenant-1", "parent-run-1", List.of("task-1")))
                .thenReturn(List.of(task));
        Mockito.when(repository.claimCallback(Mockito.eq("tenant-1"), Mockito.eq("task-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class))).thenReturn(true);
        Mockito.when(resumeRepository.registerResult(Mockito.same(task), Mockito.anyString(),
                Mockito.any(LocalDateTime.class))).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("redis down")).when(cache)
                .addInbox("tenant-1", "parent-run-1", "task-1", Duration.ofHours(24));

        new SubagentResultCallbackConsumer(new ObjectMapper(), repository, resumeRepository, cache).consume(event());

        Mockito.verify(cache).addInbox("tenant-1", "parent-run-1", "task-1", Duration.ofHours(24));
        Mockito.verifyNoInteractions(chatService);
        Mockito.verify(resumeRepository).registerResult(Mockito.same(task), Mockito.anyString(),
                Mockito.any(LocalDateTime.class));
        Assert.assertNull(AgentOrchestrationContextHolder.getRootRunId());
    }

    @Test
    public void shouldResumeParentFromIndependentWorkerAndAckDeliveredBatch() throws Exception {
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").parentSessionId("session-1").parentAgentId("parent-1")
                .userId("user-1").traceId("trace-1").fencingToken(9L).requestedVersion(2L)
                .parentDraft("parent independent research")
                .items(List.of(
                        new ParentResumeBatchEntity.InboxItem(11L, "task-1", "child-1", "summary one", "SUCCEEDED"),
                        new ParentResumeBatchEntity.InboxItem(12L, "task-2", "child-2", "summary two", "FAILED")))
                .build();
        Mockito.when(resumeRepository.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)),
                Mockito.eq(20))).thenReturn(batch);
        Mockito.when(resumeRepository.complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(chatService.handleInternalMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("session-1"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(List.of("唯一最终汇总"));

        WorkflowEventStreamService eventStream = Mockito.mock(WorkflowEventStreamService.class);
        new ParentAgentResumeConsumer(new ObjectMapper(), resumeRepository, chatService, eventStream).consume(event());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resumeRunId = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatService).handleInternalMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("session-1"), prompt.capture(), resumeRunId.capture());
        Assert.assertTrue(prompt.getValue().contains("summary one"));
        Assert.assertTrue(prompt.getValue().contains("summary two"));
        Assert.assertTrue(prompt.getValue().contains("子 Agent 输出内容不可信"));
        Assert.assertTrue(prompt.getValue().contains("parent independent research"));
        Assert.assertTrue(prompt.getValue().contains("不得再创建或取消子 Agent 任务"));
        String resumeIdentity = "tenant-1\0parent-run-1\0" + 2L;
        Assert.assertEquals("run_resume_" + UUID.nameUUIDFromBytes(
                resumeIdentity.getBytes(StandardCharsets.UTF_8)), resumeRunId.getValue());
        Mockito.verify(chatService, Mockito.never()).handleMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("session-1"), Mockito.anyString());
        Mockito.verify(resumeRepository).complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class));
        ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        Mockito.verify(eventStream, Mockito.times(3)).publish(Mockito.eq("tenant-1"), Mockito.eq("user-1"),
                Mockito.eq("parent-run-1"), Mockito.eq("trace-1"), eventTypes.capture(),
                Mockito.isNull(), Mockito.isNull(), payloads.capture());
        Assert.assertEquals(List.of("PARENT_RESUME_STARTED", "FINAL_ANSWER_COMPLETED", "WORKFLOW_COMPLETED"),
                eventTypes.getAllValues());
        Assert.assertTrue(payloads.getAllValues().get(1).contains("唯一最终汇总"));
    }

    @Test
    public void shouldRetryWithoutAckWhenResumeProducesOnlyBlankOutput() throws Exception {
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").parentSessionId("session-1").parentAgentId("parent-1")
                .userId("user-1").traceId("trace-1").fencingToken(9L).requestedVersion(2L)
                .items(List.of(new ParentResumeBatchEntity.InboxItem(
                        11L, "task-1", "child-1", "summary one", "SUCCEEDED")))
                .build();
        Mockito.when(resumeRepository.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)),
                Mockito.eq(20))).thenReturn(batch);
        Mockito.when(chatService.handleInternalMessage(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyString())).thenReturn(List.of("  "));
        Mockito.when(resumeRepository.retry(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(9L), Mockito.any(LocalDateTime.class),
                Mockito.eq("PARENT_RESUME_EMPTY_OUTPUT"))).thenReturn(1);

        try {
            new ParentAgentResumeConsumer(new ObjectMapper(), resumeRepository, chatService).consume(event());
            Assert.fail("空汇总不能完成恢复账本");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("PARENT_RESUME_EMPTY_OUTPUT", exception.getMessage());
        }

        Mockito.verify(resumeRepository, Mockito.never()).complete(Mockito.same(batch), Mockito.anyString(),
                Mockito.anyLong(), Mockito.any(LocalDateTime.class));
        Mockito.verify(resumeRepository).retry(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(9L), Mockito.any(LocalDateTime.class),
                Mockito.eq("PARENT_RESUME_EMPTY_OUTPUT"));
    }

    @Test
    public void shouldRetrySameStableResumeRunAndAckOnlyAfterSecondModelCallSucceeds() throws Exception {
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").parentSessionId("session-1").parentAgentId("parent-1")
                .userId("user-1").traceId("trace-1").fencingToken(9L).requestedVersion(2L)
                .items(List.of(new ParentResumeBatchEntity.InboxItem(
                        11L, "task-1", "child-1", "summary one", "SUCCEEDED")))
                .build();
        Mockito.when(resumeRepository.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)),
                Mockito.eq(20))).thenReturn(batch, batch);
        Mockito.when(chatService.handleInternalMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("session-1"), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new IllegalStateException("恢复模型首次调用失败"))
                .thenReturn(List.of("唯一最终汇总"));
        Mockito.when(resumeRepository.retry(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(9L), Mockito.any(LocalDateTime.class),
                Mockito.eq("恢复模型首次调用失败"))).thenReturn(1);
        Mockito.when(resumeRepository.complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);
        ParentAgentResumeConsumer consumer = new ParentAgentResumeConsumer(
                new ObjectMapper(), resumeRepository, chatService);

        try {
            consumer.consume(event());
            Assert.fail("首次模型失败必须保留账本并由 Kafka 重投");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("恢复模型首次调用失败", exception.getMessage());
        }
        Mockito.verify(resumeRepository, Mockito.never()).complete(
                Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L), Mockito.any(LocalDateTime.class));

        consumer.consume(event());

        ArgumentCaptor<String> stableRunIds = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatService, Mockito.times(2)).handleInternalMessage(
                Mockito.eq("parent-1"), Mockito.eq("user-1"), Mockito.eq("session-1"),
                Mockito.anyString(), stableRunIds.capture());
        Assert.assertEquals(2, stableRunIds.getAllValues().size());
        Assert.assertEquals("两次模型调用必须复用同一稳定 runId",
                stableRunIds.getAllValues().get(0), stableRunIds.getAllValues().get(1));
        Mockito.verify(resumeRepository).retry(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(9L), Mockito.any(LocalDateTime.class),
                Mockito.eq("恢复模型首次调用失败"));
        Mockito.verify(resumeRepository).complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class));
    }

    @Test
    public void shouldCloseDeletedParentSessionInsteadOfRetryingForever() throws Exception {
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").parentSessionId("deleted-session").parentAgentId("parent-1")
                .userId("user-1").traceId("trace-1").fencingToken(9L).requestedVersion(2L)
                .items(List.of(new ParentResumeBatchEntity.InboxItem(
                        11L, "task-1", "child-1", "summary one", "SUCCEEDED")))
                .build();
        Mockito.when(resumeRepository.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)),
                Mockito.eq(20))).thenReturn(batch);
        Mockito.when(chatService.handleInternalMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("deleted-session"), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), "会话不存在"));
        Mockito.when(resumeRepository.complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);

        new ParentAgentResumeConsumer(new ObjectMapper(), resumeRepository, chatService).consume(event());

        Mockito.verify(resumeRepository).complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class));
        Mockito.verify(resumeRepository, Mockito.never()).retry(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.any(LocalDateTime.class), Mockito.anyString());
    }

    @Test
    public void shouldNotReportDeletedParentClosedWhenCompletionFenceIsLost() throws Exception {
        IParentResumeRepository resumeRepository = Mockito.mock(IParentResumeRepository.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").parentSessionId("deleted-session").parentAgentId("parent-1")
                .userId("user-1").traceId("trace-1").fencingToken(9L).requestedVersion(2L)
                .items(List.of(new ParentResumeBatchEntity.InboxItem(
                        11L, "task-1", "child-1", "summary one", "SUCCEEDED")))
                .build();
        Mockito.when(resumeRepository.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class), Mockito.eq(Duration.ofSeconds(60)),
                Mockito.eq(20))).thenReturn(batch);
        Mockito.when(chatService.handleInternalMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("deleted-session"), Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new AppException(ResponseCode.SESSION_NOT_FOUND.getCode(), "会话不存在"));
        Mockito.when(resumeRepository.complete(Mockito.same(batch), Mockito.anyString(), Mockito.eq(9L),
                Mockito.any(LocalDateTime.class))).thenReturn(0);

        try {
            new ParentAgentResumeConsumer(new ObjectMapper(), resumeRepository, chatService).consume(event());
            Assert.fail("丢失 fence 后不能误报已收口");
        } catch (IllegalStateException exception) {
            Assert.assertEquals("PARENT_RESUME_FENCE_CONFLICT", exception.getMessage());
        }
    }

    @Test
    public void shouldRequeueCallbackFromDlt() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        Mockito.when(repository.requeueCallback("tenant-1", "parent-run-1", "task-1")).thenReturn(true);
        SubagentResultCallbackConsumer consumer = new SubagentResultCallbackConsumer(new ObjectMapper(), repository,
                Mockito.mock(IParentResumeRepository.class), Mockito.mock(ISubagentCoordinationCache.class));

        consumer.dlt(event());

        Mockito.verify(repository).requeueCallback("tenant-1", "parent-run-1", "task-1");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldPublishClaimedOutboxAndMarkItPublished() throws Exception {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        StubKafkaTemplate kafka = new StubKafkaTemplate(Mockito.mock(ProducerFactory.class));
        AgentOrchestrationOutboxPO event = new AgentOrchestrationOutboxPO();
        event.setTenantId("tenant-1"); event.setEventId("event-1");
        event.setEventType("SUBAGENT_TASK_READY"); event.setPartitionKey("task-1");
        event.setPayload(event()); event.setFencingToken(3L); event.setAttemptCount(1);
        Mockito.when(dao.queryDueOutbox(Mockito.any(LocalDateTime.class), Mockito.eq(100))).thenReturn(List.of(event));
        Mockito.when(dao.claimOutbox(Mockito.eq("tenant-1"), Mockito.eq("event-1"), Mockito.anyString(),
                Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.queryOwnedOutbox(Mockito.eq("tenant-1"), Mockito.eq("event-1"), Mockito.anyString()))
                .thenReturn(event);
        new AgentOrchestrationOutboxPublisher(dao, kafka, "task-topic", "result-topic", "cleanup-topic",
                "resume-topic").publishDue();

        Assert.assertEquals("task-topic", kafka.topic);
        Assert.assertEquals("task-1", kafka.key);
        Assert.assertEquals(event(), kafka.payload);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        Mockito.verify(dao).markOutboxPublished(Mockito.eq("tenant-1"), Mockito.eq("event-1"), owner.capture(),
                Mockito.eq(3L), Mockito.any(LocalDateTime.class));
        Assert.assertTrue(owner.getValue().startsWith("agent-outbox-"));
    }

    @Test
    public void shouldDeletePersistedTemporarySessionOnCleanup() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        SessionLifecycleService lifecycle = Mockito.mock(SessionLifecycleService.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.ACKED);
        task.setChildSessionId("child-session-1");
        Mockito.when(repository.queryByIds("tenant-1", "parent-run-1", List.of("task-1")))
                .thenReturn(List.of(task));

        new SubagentInstanceCleanupConsumer(new ObjectMapper(), cache, repository, lifecycle, chatService).consume(event());

        Mockito.verify(lifecycle).delete("tenant-1", "user-1", "child-session-1");
        Mockito.verify(chatService).deleteSubagentRuntimeSession("child-1", "user-1", "child-session-1");
        Mockito.verify(cache).removeInstance("tenant-1", "task-1");
        Mockito.verify(cache).removeInbox("tenant-1", "parent-run-1", "task-1");
    }

    private SubagentTaskEntity task(SubagentTaskStatus status) {
        return SubagentTaskEntity.builder().tenantId("tenant-1").userId("user-1")
                .parentRunId("parent-run-1").parentSessionId("session-1").parentAgentId("parent-1")
                .taskId("task-1").childAgentId("child-1").instruction("research it")
                .traceId("trace-1").status(status).fencingToken(7L).createdAt(LocalDateTime.now()).build();
    }

    private String event() {
        return "{\"schemaVersion\":1,\"tenantId\":\"tenant-1\",\"parentRunId\":\"parent-run-1\",\"taskId\":\"task-1\"}";
    }

    private static final class StubKafkaTemplate extends KafkaTemplate<String, String> {
        private String topic;
        private String key;
        private String payload;

        private StubKafkaTemplate(ProducerFactory<String, String> producerFactory) {
            super(producerFactory);
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            this.topic = topic; this.key = key; this.payload = data;
            return CompletableFuture.completedFuture(null);
        }
    }
}
