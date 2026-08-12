package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentCoordinationCache;
import cn.bugstack.ai.domain.agent.adapter.repository.ISubagentTaskRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.infrastructure.agent.AgentOrchestrationOutboxPublisher;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.trigger.listener.SubagentResultCallbackConsumer;
import cn.bugstack.ai.trigger.listener.SubagentTaskConsumer;
import cn.bugstack.ai.types.context.AgentOrchestrationContextHolder;
import cn.bugstack.ai.types.context.TenantContextHolder;
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
import java.util.List;
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
        Mockito.when(chatService.handleMessage("child-1", "user-1", "research it"))
                .thenReturn(List.of("partial", "final answer"));
        Mockito.when(repository.complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L))).thenReturn(1);

        new SubagentTaskConsumer(new ObjectMapper(), repository, cache, chatService).consume(event());

        Assert.assertEquals(SubagentTaskStatus.SUCCEEDED, task.getStatus());
        Assert.assertEquals("final answer", task.getResultText());
        Mockito.verify(cache).putInstance(Mockito.same(task), Mockito.eq(Duration.ofHours(2)));
        Mockito.verify(repository).complete(Mockito.same(task), Mockito.anyString(), Mockito.eq(7L));
        Assert.assertNull(TenantContextHolder.getTenantId());
    }

    @Test
    public void shouldWakeParentThenAtomicallyAckCallback() throws Exception {
        ISubagentTaskRepository repository = Mockito.mock(ISubagentTaskRepository.class);
        ISubagentCoordinationCache cache = Mockito.mock(ISubagentCoordinationCache.class);
        IChatService chatService = Mockito.mock(IChatService.class);
        SubagentTaskEntity task = task(SubagentTaskStatus.SUCCEEDED);
        Mockito.when(repository.queryByIds("tenant-1", "parent-run-1", List.of("task-1")))
                .thenReturn(List.of(task));
        Mockito.when(repository.claimCallback(Mockito.eq("tenant-1"), Mockito.eq("task-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class))).thenReturn(true);
        Mockito.when(repository.finishCallback(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("task-1"), Mockito.anyString(), Mockito.any(LocalDateTime.class))).thenReturn(1);

        new SubagentResultCallbackConsumer(new ObjectMapper(), repository, cache, chatService).consume(event());

        Mockito.verify(cache).addInbox("tenant-1", "parent-run-1", "task-1", Duration.ofHours(24));
        Mockito.verify(chatService).handleMessage(Mockito.eq("parent-1"), Mockito.eq("user-1"),
                Mockito.eq("session-1"), Mockito.contains("read_subagent_result"));
        Mockito.verify(repository).finishCallback(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("task-1"), Mockito.anyString(), Mockito.any(LocalDateTime.class));
        Assert.assertNull(AgentOrchestrationContextHolder.getRootRunId());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldPublishClaimedOutboxAndMarkItPublished() throws Exception {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        StubKafkaTemplate kafka = new StubKafkaTemplate(Mockito.mock(ProducerFactory.class));
        AgentOrchestrationOutboxPO event = new AgentOrchestrationOutboxPO();
        event.setTenantId("tenant-1"); event.setEventId("event-1");
        event.setEventType("SUBAGENT_TASK_READY"); event.setPartitionKey("parent-run-1");
        event.setPayload(event()); event.setFencingToken(3L); event.setAttemptCount(1);
        Mockito.when(dao.queryDueOutbox(Mockito.any(LocalDateTime.class), Mockito.eq(100))).thenReturn(List.of(event));
        Mockito.when(dao.claimOutbox(Mockito.eq("tenant-1"), Mockito.eq("event-1"), Mockito.anyString(),
                Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.queryOwnedOutbox(Mockito.eq("tenant-1"), Mockito.eq("event-1"), Mockito.anyString()))
                .thenReturn(event);
        new AgentOrchestrationOutboxPublisher(dao, kafka, "task-topic", "result-topic", "cleanup-topic").publishDue();

        Assert.assertEquals("task-topic", kafka.topic);
        Assert.assertEquals("parent-run-1", kafka.key);
        Assert.assertEquals(event(), kafka.payload);
        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        Mockito.verify(dao).markOutboxPublished(Mockito.eq("tenant-1"), Mockito.eq("event-1"), owner.capture(),
                Mockito.eq(3L), Mockito.any(LocalDateTime.class));
        Assert.assertTrue(owner.getValue().startsWith("agent-outbox-"));
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
