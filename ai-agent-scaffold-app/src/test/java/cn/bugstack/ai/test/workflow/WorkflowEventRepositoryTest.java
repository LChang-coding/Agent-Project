package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.adapter.repository.WorkflowEventRepository;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.IntelligentWorkflowRunPO;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工作流事件仓储的序号与根 trace 一致性测试。 */
public class WorkflowEventRepositoryTest {

    @Test
    public void shouldAppendWithSequenceOwnedByLockedRun() {
        IWorkflowRunEventDao dao = mock(IWorkflowRunEventDao.class);
        IntelligentWorkflowRunPO run = run("trace_root", 8L, 3L);
        when(dao.lockRun("tenant_1", "user_1", "run_1")).thenReturn(run);
        when(dao.advanceSequence("tenant_1", "run_1", 3L)).thenReturn(1);
        when(dao.insert(any())).thenReturn(1);

        WorkflowRunEventEntity result = new WorkflowEventRepository(dao).append(event("trace_root"));

        Assert.assertEquals(Long.valueOf(8L), result.getSequence());
        ArgumentCaptor<cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO> captor =
                ArgumentCaptor.forClass(cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO.class);
        verify(dao).insert(captor.capture());
        Assert.assertEquals(Long.valueOf(8L), captor.getValue().getSequence());
        Assert.assertEquals("trace_root", captor.getValue().getTraceId());
    }

    @Test
    public void shouldRejectEventWithDifferentTraceBeforeWriting() {
        IWorkflowRunEventDao dao = mock(IWorkflowRunEventDao.class);
        when(dao.lockRun("tenant_1", "user_1", "run_1")).thenReturn(run("trace_root", 1L, 0L));
        try {
            new WorkflowEventRepository(dao).append(event("trace_other"));
            Assert.fail("不同 trace 的事件不能进入同一个运行");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_TRACE_MISMATCH", exception.getCode());
        }
        verify(dao, never()).insert(any());
    }

    private IntelligentWorkflowRunPO run(String traceId, long nextSequence, long revision) {
        IntelligentWorkflowRunPO run = new IntelligentWorkflowRunPO();
        run.setTenantId("tenant_1"); run.setUserId("user_1"); run.setRunId("run_1");
        run.setTraceId(traceId); run.setNextSequence(nextSequence); run.setRevision(revision);
        return run;
    }

    private WorkflowRunEventEntity event(String traceId) {
        return WorkflowRunEventEntity.builder().tenantId("tenant_1").userId("user_1").runId("run_1")
                .eventId("event_1").schemaVersion("workflow-event-v1").eventType("NODE_STARTED")
                .payloadJson("{}").traceId(traceId).occurredAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusDays(30)).build();
    }
}
