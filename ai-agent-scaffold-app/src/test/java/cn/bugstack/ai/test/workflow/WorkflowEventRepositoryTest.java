package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkflowEventRepository;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkflowEventWriteTransaction;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.lang.reflect.Method;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/** 工作流事件仓储的序号与根 trace 一致性测试。 */
public class WorkflowEventRepositoryTest {

    @Test
    public void shouldJoinRunCreationTransactionInsteadOfOpeningRequiresNewConnection() throws Exception {
        Method method = WorkflowEventWriteTransaction.class.getMethod("appendOnce", WorkflowRunEventEntity.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        Assert.assertNotNull(transactional);
        Assert.assertEquals(Propagation.REQUIRED, transactional.propagation());
    }

    @Test
    public void shouldAppendWithSequenceOwnedByLockedRun() {
        IWorkflowRunEventDao dao = mock(IWorkflowRunEventDao.class);
        IWorkflowEventCursorRepository cursor = mock(IWorkflowEventCursorRepository.class);
        when(cursor.allocate("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED")).thenReturn(8L);
        when(dao.insert(any())).thenReturn(1);

        WorkflowRunEventEntity result = new WorkflowEventRepository(dao, cursor).append(event("trace_root"));

        Assert.assertEquals(Long.valueOf(8L), result.getSequence());
        ArgumentCaptor<cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO> captor =
                ArgumentCaptor.forClass(cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO.class);
        verify(dao).insert(captor.capture());
        Assert.assertEquals(Long.valueOf(8L), captor.getValue().getSequence());
        Assert.assertEquals("trace_root", captor.getValue().getTraceId());
    }

    @Test
    public void shouldDelegateScopeAndTraceValidationToGenericCursor() {
        IWorkflowRunEventDao dao = mock(IWorkflowRunEventDao.class);
        IWorkflowEventCursorRepository cursor = mock(IWorkflowEventCursorRepository.class);
        when(cursor.allocate("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED")).thenReturn(1L);
        when(dao.insert(any())).thenReturn(1);

        new WorkflowEventRepository(dao, cursor).append(event("trace_root"));

        verify(cursor).allocate("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED");
    }

    @Test
    public void shouldRetryDeadlockTwiceAndThenPersist() {
        IWorkflowRunEventDao dao = mock(IWorkflowRunEventDao.class);
        WorkflowEventWriteTransaction writer = mock(WorkflowEventWriteTransaction.class);
        WorkflowRunEventEntity event = event("trace_root");
        when(writer.appendOnce(event))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock-1", null))
                .thenThrow(new DeadlockLoserDataAccessException("deadlock-2", null))
                .thenReturn(event);

        Assert.assertSame(event, new WorkflowEventRepository(dao, writer).append(event));
        verify(writer, times(3)).appendOnce(event);
    }

    private WorkflowRunEventEntity event(String traceId) {
        return WorkflowRunEventEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .eventId("event_1").schemaVersion("workflow-event-v1").eventType("NODE_STARTED")
                .payloadJson("{}").traceId(traceId).occurredAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30)).build();
    }
}
