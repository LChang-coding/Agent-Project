package cn.bugstack.ai.test.agent;

import cn.bugstack.ai.domain.agent.adapter.repository.IParentResumeRepository;
import cn.bugstack.ai.domain.agent.model.entity.SubagentTaskEntity;
import cn.bugstack.ai.domain.agent.model.valobj.SubagentTaskStatus;
import cn.bugstack.ai.infrastructure.adapter.repository.SubagentTaskRepository;
import cn.bugstack.ai.infrastructure.dao.ISubagentTaskDao;
import cn.bugstack.ai.infrastructure.dao.po.AgentOrchestrationOutboxPO;
import cn.bugstack.ai.infrastructure.dao.po.SubagentTaskPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public class SubagentRecoveryRepositoryTest {

    @Test
    public void shouldPartitionReadyEventsByTaskAndRecoverExpiredLeases() {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        Mockito.when(dao.insertTask(Mockito.any())).thenReturn(1);
        Mockito.when(dao.insertOutbox(Mockito.any())).thenReturn(1);
        IParentResumeRepository parentResumeRepository = Mockito.mock(IParentResumeRepository.class);
        Mockito.when(parentResumeRepository.tryPrepareWait(Mockito.any(), Mockito.any(LocalDateTime.class)))
                .thenReturn(true);
        SubagentTaskRepository repository = new SubagentTaskRepository(dao, new ObjectMapper(), parentResumeRepository);
        SubagentTaskEntity task = task();

        repository.createBatchAndEnqueue(List.of(task));

        Mockito.verify(parentResumeRepository).tryPrepareWait(Mockito.same(task), Mockito.any(LocalDateTime.class));
        ArgumentCaptor<AgentOrchestrationOutboxPO> outbox = ArgumentCaptor.forClass(AgentOrchestrationOutboxPO.class);
        Mockito.verify(dao).insertOutbox(outbox.capture());
        Assert.assertEquals("task-1", outbox.getValue().getPartitionKey());

        SubagentTaskPO expiredExecution = po("RUNNING");
        SubagentTaskPO expiredCallback = po("SUCCEEDED");
        expiredCallback.setCallbackOwner("callback-1");
        Mockito.when(dao.queryExpiredExecutions(Mockito.any(LocalDateTime.class), Mockito.eq(100)))
                .thenReturn(List.of(expiredExecution));
        Mockito.when(dao.resetExpiredExecution("tenant-1", "task-1", 7L, expiredExecution.getLeaseExpiresAt()))
                .thenReturn(1);
        Mockito.when(dao.queryExpiredCallbacks(Mockito.any(LocalDateTime.class), Mockito.eq(100)))
                .thenReturn(List.of(expiredCallback));
        Mockito.when(dao.resetExpiredCallback("tenant-1", "task-1", "callback-1",
                expiredCallback.getCallbackClaimedAt())).thenReturn(1);

        Assert.assertEquals(2, repository.recoverExpired(LocalDateTime.now(), Duration.ofMinutes(5), 100));
        Mockito.verify(dao, Mockito.times(3)).insertOutbox(Mockito.any());
    }

    @Test
    public void shouldTryWaitAllActivationAfterCancellationWithoutAResultCallback() {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        IParentResumeRepository parentResumeRepository = Mockito.mock(IParentResumeRepository.class);
        SubagentTaskRepository repository = new SubagentTaskRepository(dao, new ObjectMapper(), parentResumeRepository);
        LocalDateTime cancelledAt = LocalDateTime.now();
        Mockito.when(dao.cancel("tenant-1", "parent-1", List.of("task-1"), cancelledAt)).thenReturn(1);

        Assert.assertEquals(1, repository.cancel("tenant-1", "parent-1", List.of("task-1"), cancelledAt));

        Mockito.verify(parentResumeRepository).tryActivate("tenant-1", "parent-1", cancelledAt);
    }

    @Test
    public void shouldTreatExhaustedCallbackAsDeadAndReleaseWaitAll() {
        ISubagentTaskDao dao = Mockito.mock(ISubagentTaskDao.class);
        IParentResumeRepository parentResumeRepository = Mockito.mock(IParentResumeRepository.class);
        SubagentTaskRepository repository = new SubagentTaskRepository(dao, new ObjectMapper(), parentResumeRepository);
        Mockito.when(dao.prepareCallbackReplay("tenant-1", "parent-1", "task-1")).thenReturn(0);
        Mockito.when(dao.markCallbackDead("tenant-1", "parent-1", "task-1")).thenReturn(1);
        Mockito.when(dao.insertOutbox(Mockito.any())).thenReturn(1);

        Assert.assertFalse(repository.requeueCallback("tenant-1", "parent-1", "task-1"));

        ArgumentCaptor<AgentOrchestrationOutboxPO> cleanup = ArgumentCaptor.forClass(AgentOrchestrationOutboxPO.class);
        Mockito.verify(dao).insertOutbox(cleanup.capture());
        Assert.assertEquals("SUBAGENT_INSTANCE_CLEANUP", cleanup.getValue().getEventType());
        Mockito.verify(parentResumeRepository).tryActivate(
                Mockito.eq("tenant-1"), Mockito.eq("parent-1"), Mockito.any(LocalDateTime.class));
    }

    private SubagentTaskEntity task() {
        return SubagentTaskEntity.builder().tenantId("tenant-1").userId("user-1")
                .parentRunId("parent-1").parentSessionId("session-1").parentAgentId("agent-1")
                .taskId("task-1").childAgentId("child-1").instruction("do it").functionCallId("call-1")
                .status(SubagentTaskStatus.READY).attempt(0).fencingToken(0L).createdAt(LocalDateTime.now()).build();
    }

    private SubagentTaskPO po(String status) {
        SubagentTaskPO value = new SubagentTaskPO();
        value.setTenantId("tenant-1"); value.setUserId("user-1"); value.setParentRunId("parent-1");
        value.setTaskId("task-1"); value.setStatus(status); value.setFencingToken(7L);
        value.setTraceId("trace-1"); value.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        value.setCallbackClaimedAt(LocalDateTime.now().minusMinutes(6));
        return value;
    }
}
