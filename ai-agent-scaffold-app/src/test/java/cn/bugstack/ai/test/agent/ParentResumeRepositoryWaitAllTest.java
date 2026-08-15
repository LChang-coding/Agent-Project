package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.model.entity.ParentResumeBatchEntity;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.infrastructure.adapter.repository.ParentResumeRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.SubagentTaskRepository;
import cn.bugstack.ai.infrastructure.dao.IParentResumeDao;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentInboxItemPO;
import cn.bugstack.ai.infrastructure.dao.po.ParentResumeRequestPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Parent Resume WAIT_ALL 门闩与全量结果读取的回归测试。 */
public class ParentResumeRepositoryWaitAllTest {

    @Test
    public void shouldOnlyRegisterAnIntermediateCallbackWithoutRequestingResume() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        Mockito.when(dao.markCallbackRegistered("tenant-1", "task-1", "callback-1")).thenReturn(1);

        Assert.assertTrue(repository.registerResult(task("task-1"), "callback-1", LocalDateTime.now()));

        Mockito.verify(dao).insertInbox(Mockito.argThat(item -> "task-1".equals(item.getTaskId())));
        Mockito.verify(dao, Mockito.never()).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
    }

    @Test
    public void shouldRequestResumeOnlyOnceWhenCallbacksConverge() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        Mockito.when(dao.markCallbackRegistered(Mockito.eq("tenant-1"), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(1);
        Mockito.when(dao.activateIfReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.any(LocalDateTime.class))).thenReturn(0, 1);

        Assert.assertTrue(repository.registerResult(task("task-1"), "callback-1", LocalDateTime.now()));
        Assert.assertTrue(repository.registerResult(task("task-2"), "callback-2", LocalDateTime.now()));

        Mockito.verify(dao, Mockito.times(2)).insertInbox(Mockito.any(ParentInboxItemPO.class));
        Mockito.verify(dao, Mockito.times(1)).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
    }

    @Test
    public void shouldClaimEveryTerminalSummaryInsteadOfOnlyTheIncrementAfterCursor() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        ParentResumeRequestPO request = request();
        request.setInboxCursor(99L);
        Mockito.when(dao.claim(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"), Mockito.eq("worker-1"),
                Mockito.any(LocalDateTime.class), Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.queryOwned("tenant-1", "parent-run-1", "worker-1")).thenReturn(request);
        Mockito.when(dao.queryAllTerminalResults("tenant-1", "parent-run-1"))
                .thenReturn(List.of(inbox(11L, "task-1"), inbox(12L, "task-2")));

        ParentResumeBatchEntity batch = repository.claim("tenant-1", "parent-run-1", "worker-1",
                LocalDateTime.now(), Duration.ofSeconds(60), 20);

        Assert.assertNotNull(batch);
        Assert.assertEquals("parent draft", batch.getParentDraft());
        Assert.assertEquals(List.of("task-1", "task-2"),
                batch.getItems().stream().map(ParentResumeBatchEntity.InboxItem::taskId).toList());
        Mockito.verify(dao).queryAllTerminalResults("tenant-1", "parent-run-1");
    }

    @Test
    public void shouldIgnoreDuplicateOrLateCallbackWithoutAnotherResume() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        Mockito.when(dao.markCallbackRegistered("tenant-1", "task-1", "late-callback")).thenReturn(0);

        Assert.assertFalse(repository.registerResult(task("task-1"), "late-callback", LocalDateTime.now()));

        Mockito.verify(dao, Mockito.never()).insertInbox(Mockito.any(ParentInboxItemPO.class));
        Mockito.verify(dao, Mockito.never()).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
    }

    @Test
    public void shouldKeepParentDraftWaitingUntilChildrenAreReady() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        Mockito.when(dao.markParentReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("parent draft"), Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.activateIfReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.any(LocalDateTime.class))).thenReturn(0);

        Assert.assertTrue(repository.markParentReady("tenant-1", "parent-run-1", "parent draft",
                LocalDateTime.now()));

        Mockito.verify(dao, Mockito.never()).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
    }

    @Test
    public void shouldActivateWhenParentReadyClosesTheSecondBarrier() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        ParentResumeRequestPO request = request();
        Mockito.when(dao.markParentReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("parent draft"), Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.activateIfReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.query("tenant-1", "parent-run-1")).thenReturn(request);

        Assert.assertTrue(repository.markParentReady("tenant-1", "parent-run-1", "parent draft",
                LocalDateTime.now()));

        Mockito.verify(dao, Mockito.times(1)).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
    }

    @Test
    public void shouldPrepareAndExposeAwaitingSummaryState() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        Mockito.when(dao.countAwaitingSummary("tenant-1", "parent-run-1")).thenReturn(1);

        repository.prepareWait(task("task-1"), LocalDateTime.now());

        Mockito.verify(dao).prepareWait(Mockito.argThat(request -> "WAITING".equals(request.getStatus())
                && Boolean.FALSE.equals(request.getParentReady())));
        Assert.assertTrue(repository.isAwaitingSummary("tenant-1", "parent-run-1"));
    }

    @Test
    public void shouldRecoverTerminalParentBeforeOpeningUnreadyWaitAll() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        ParentResumeRequestPO crashed = request();
        crashed.setStatus("WAITING");
        crashed.setParentReady(false);
        crashed.setParentDraft(null);
        LocalDateTime now = LocalDateTime.now();
        Mockito.when(dao.queryRecoveryCandidates(Mockito.eq(now), Mockito.any(LocalDateTime.class), Mockito.eq(100)))
                .thenReturn(List.of(crashed));
        Mockito.when(dao.lockTerminalParentRun("tenant-1", "user-1", "parent-run-1"))
                .thenReturn("completed");
        Mockito.when(dao.markRecoveryNotified(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("WAITING"), Mockito.eq(7L), Mockito.eq(now), Mockito.any(LocalDateTime.class)))
                .thenReturn(1);
        Mockito.when(dao.markParentReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(now))).thenReturn(1);
        Mockito.when(dao.activateIfReady("tenant-1", "parent-run-1", now)).thenReturn(1);
        Mockito.when(dao.query("tenant-1", "parent-run-1")).thenReturn(crashed);

        Assert.assertEquals(1, repository.recoverDue(now, 100));

        org.mockito.InOrder order = Mockito.inOrder(dao);
        order.verify(dao).lockTerminalParentRun("tenant-1", "user-1", "parent-run-1");
        order.verify(dao).markRecoveryNotified(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("WAITING"), Mockito.eq(7L), Mockito.eq(now), Mockito.any(LocalDateTime.class));
        order.verify(dao).markParentReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.eq(now));
        order.verify(dao).activateIfReady("tenant-1", "parent-run-1", now);
        order.verify(dao).insertOutbox(Mockito.argThat(event ->
                "PARENT_RESUME_REQUESTED".equals(event.getEventType())));
    }

    @Test
    public void shouldNeverForceAnExecutableParentToTerminalBecauseItIsOld() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        ParentResumeRequestPO live = request();
        live.setStatus("WAITING");
        live.setParentReady(false);
        LocalDateTime now = LocalDateTime.now();
        Mockito.when(dao.queryRecoveryCandidates(Mockito.eq(now), Mockito.any(LocalDateTime.class), Mockito.eq(100)))
                .thenReturn(List.of(live));
        Mockito.when(dao.lockTerminalParentRun("tenant-1", "user-1", "parent-run-1"))
                .thenReturn(null);

        Assert.assertEquals(0, repository.recoverDue(now, 100));

        Mockito.verify(dao, Mockito.never()).markRecoveryNotified(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any());
        Mockito.verify(dao, Mockito.never()).markParentReady(Mockito.anyString(), Mockito.anyString(),
                Mockito.anyString(), Mockito.any());
        Mockito.verify(dao, Mockito.never()).insertOutbox(Mockito.any());
    }

    @Test
    public void shouldAckDeliveredResultsWithoutRepeatingCancelledTaskCleanup() {
        IParentResumeDao dao = Mockito.mock(IParentResumeDao.class);
        ParentResumeRepository repository = new ParentResumeRepository(dao, new ObjectMapper());
        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").traceId("trace-1").requestedVersion(1L).fencingToken(7L)
                .items(List.of(
                        new ParentResumeBatchEntity.InboxItem(11L, "task-1", "child-1", "done", "SUCCEEDED"),
                        new ParentResumeBatchEntity.InboxItem(0L, "task-2", "child-2", "", "CANCELLED")))
                .build();
        Mockito.when(dao.completeRequest(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("worker-1"), Mockito.eq(7L), Mockito.eq(1L), Mockito.eq(11L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(dao.finishTaskDelivery(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.anyString(), Mockito.any(LocalDateTime.class))).thenReturn(1);

        Assert.assertEquals(1, repository.complete(batch, "worker-1", 7L, LocalDateTime.now()));

        Mockito.verify(dao).markInboxConsumed(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq(List.of("task-1", "task-2")), Mockito.any(LocalDateTime.class));
        Mockito.verify(dao, Mockito.times(1)).finishTaskDelivery(Mockito.eq("tenant-1"),
                Mockito.eq("parent-run-1"), Mockito.anyString(), Mockito.any(LocalDateTime.class));
        org.mockito.ArgumentCaptor<AgentOrchestrationOutboxPO> events =
                org.mockito.ArgumentCaptor.forClass(AgentOrchestrationOutboxPO.class);
        Mockito.verify(dao, Mockito.times(1)).insertOutbox(events.capture());
        Assert.assertEquals(List.of("SUBAGENT_INSTANCE_CLEANUP"),
                events.getAllValues().stream().map(AgentOrchestrationOutboxPO::getEventType)
                        .collect(Collectors.toList()));
    }

    @Test
    public void shouldNotPublishSecondCleanupWhenDeadCallbackIsAckedAfterWaitAll() {
        IParentResumeDao parentDao = Mockito.mock(IParentResumeDao.class);
        ISubagentTaskDao taskDao = Mockito.mock(ISubagentTaskDao.class);
        ParentResumeRepository parentRepository = new ParentResumeRepository(parentDao, new ObjectMapper());
        SubagentTaskRepository taskRepository = new SubagentTaskRepository(
                taskDao, new ObjectMapper(), parentRepository);
        List<AgentOrchestrationOutboxPO> cleanupEvents = new ArrayList<>();
        Mockito.doAnswer(invocation -> {
            AgentOrchestrationOutboxPO event = invocation.getArgument(0);
            if ("SUBAGENT_INSTANCE_CLEANUP".equals(event.getEventType())) cleanupEvents.add(event);
            return 1;
        }).when(taskDao).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
        Mockito.doAnswer(invocation -> {
            AgentOrchestrationOutboxPO event = invocation.getArgument(0);
            if ("SUBAGENT_INSTANCE_CLEANUP".equals(event.getEventType())) cleanupEvents.add(event);
            return 1;
        }).when(parentDao).insertOutbox(Mockito.any(AgentOrchestrationOutboxPO.class));
        Mockito.when(taskDao.prepareCallbackReplay("tenant-1", "parent-run-1", "task-dead"))
                .thenReturn(0);
        Mockito.when(taskDao.markCallbackDead("tenant-1", "parent-run-1", "task-dead"))
                .thenReturn(1);
        Mockito.when(parentDao.activateIfReady(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.any(LocalDateTime.class))).thenReturn(0);

        Assert.assertFalse(taskRepository.requeueCallback("tenant-1", "parent-run-1", "task-dead"));

        ParentResumeBatchEntity batch = ParentResumeBatchEntity.builder().tenantId("tenant-1")
                .parentRunId("parent-run-1").traceId("trace-1").requestedVersion(1L).fencingToken(7L)
                .items(List.of(new ParentResumeBatchEntity.InboxItem(
                        0L, "task-dead", "child-1", "callback exhausted", "FAILED")))
                .build();
        Mockito.when(parentDao.completeRequest(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("worker-1"), Mockito.eq(7L), Mockito.eq(1L), Mockito.eq(0L),
                Mockito.any(LocalDateTime.class))).thenReturn(1);
        Mockito.when(parentDao.finishTaskDelivery(Mockito.eq("tenant-1"), Mockito.eq("parent-run-1"),
                Mockito.eq("task-dead"), Mockito.any(LocalDateTime.class))).thenReturn(1);

        Assert.assertEquals(1, parentRepository.complete(batch, "worker-1", 7L, LocalDateTime.now()));
        Assert.assertEquals("DEAD 转终态和最终 ACK 只能产生一次清理副作用", 1, cleanupEvents.size());
    }

    private SubagentTaskEntity task(String taskId) {
        return SubagentTaskEntity.builder().tenantId("tenant-1").userId("user-1")
                .parentRunId("parent-run-1").parentSessionId("session-1").parentAgentId("parent-1")
                .taskId(taskId).childAgentId("child-1").traceId("trace-1")
                .resultSummary("summary-" + taskId).status(SubagentTaskStatus.SUCCEEDED).build();
    }

    private ParentResumeRequestPO request() {
        ParentResumeRequestPO request = new ParentResumeRequestPO();
        request.setTenantId("tenant-1"); request.setUserId("user-1"); request.setParentRunId("parent-run-1");
        request.setParentSessionId("session-1"); request.setParentAgentId("parent-1"); request.setTraceId("trace-1");
        request.setParentDraft("parent draft"); request.setRequestedVersion(1L); request.setFencingToken(7L); return request;
    }

    private ParentInboxItemPO inbox(long sequence, String taskId) {
        ParentInboxItemPO item = new ParentInboxItemPO();
        item.setSequence(sequence); item.setTaskId(taskId); item.setChildAgentId("child-" + taskId);
        item.setResultSummary("summary-" + taskId); item.setTaskStatus("SUCCEEDED"); return item;
    }
}
