package cn.bugstack.ai.test.workflow;

import cn.bugstack.ai.infrastructure.adapter.repository.WorkflowEventCursorRepository;
import cn.bugstack.ai.infrastructure.dao.IWorkflowEventCursorDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowEventCursorPO;
import cn.bugstack.ai.types.exception.AppException;
import org.junit.Assert;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通用工作流事件游标的归属、Trace 和序号分配测试。 */
public class WorkflowEventCursorRepositoryTest {

    @Test
    public void shouldAllocateFromWorkflowChatRunAndKeepLegacyIntelligentCursorMonotonic() {
        IWorkflowEventCursorDao dao = mock(IWorkflowEventCursorDao.class);
        when(dao.lockCursor("tenant_1", "user_1", "run_1"))
                .thenReturn(cursor("trace_root", 12L, 5L));
        when(dao.advanceSequence("tenant_1", "user_1", "run_1", "trace_root", 5L)).thenReturn(1);

        long sequence = new WorkflowEventCursorRepository(dao)
                .allocate("tenant_1", "user_1", "run_1", "trace_root", "NODE_STARTED");

        Assert.assertEquals(12L, sequence);
        verify(dao).insertFromWorkflowChatRun("tenant_1", "user_1", "run_1");
        verify(dao).syncIntelligentRunSequence("tenant_1", "user_1", "run_1", "trace_root", 13L);
    }

    @Test
    public void shouldRejectTraceMismatchBeforeAdvancingCursor() {
        IWorkflowEventCursorDao dao = mock(IWorkflowEventCursorDao.class);
        when(dao.lockCursor("tenant_1", "user_1", "run_1"))
                .thenReturn(cursor("trace_root", 1L, 0L));

        try {
            new WorkflowEventCursorRepository(dao)
                    .allocate("tenant_1", "user_1", "run_1", "trace_other", "NODE_STARTED");
            Assert.fail("不同根 trace 不能共用事件游标");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_TRACE_MISMATCH", exception.getCode());
        }
        verify(dao, never()).advanceSequence("tenant_1", "user_1", "run_1", "trace_other", 0L);
    }

    @Test
    public void shouldRejectRunOutsideAuthorizedWorkflowScope() {
        IWorkflowEventCursorDao dao = mock(IWorkflowEventCursorDao.class);

        try {
            new WorkflowEventCursorRepository(dao)
                    .allocate("tenant_1", "user_1", "agent_run", "trace_root", "NODE_STARTED");
            Assert.fail("Agent chat_run 不能获得工作流事件游标");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_RUN_NOT_FOUND", exception.getCode());
        }
        verify(dao, never()).advanceSequence("tenant_1", "user_1", "agent_run", "trace_root", 0L);
    }

    @Test
    public void shouldClaimOnlyOneTerminalAndRejectLaterEvents() {
        IWorkflowEventCursorDao dao = mock(IWorkflowEventCursorDao.class);
        WorkflowEventCursorPO terminalCursor = cursor("trace_root", 9L, 3L);
        terminalCursor.setTerminalEventType("WORKFLOW_CANCELLED");
        terminalCursor.setTerminalSequence(8L);
        when(dao.lockCursor("tenant_1", "user_1", "run_1")).thenReturn(terminalCursor);

        try {
            new WorkflowEventCursorRepository(dao)
                    .allocate("tenant_1", "user_1", "run_1", "trace_root", "NODE_COMPLETED");
            Assert.fail("终态之后不能追加节点事件");
        } catch (AppException exception) {
            Assert.assertEquals("WORKFLOW_EVENT_AFTER_TERMINAL", exception.getCode());
        }
        verify(dao, never()).advanceSequence("tenant_1", "user_1", "run_1", "trace_root", 3L);
    }

    private WorkflowEventCursorPO cursor(String traceId, long nextSequence, long revision) {
        WorkflowEventCursorPO cursor = new WorkflowEventCursorPO();
        cursor.setTenantId("tenant_1");
        cursor.setUserId("user_1");
        cursor.setRunId("run_1");
        cursor.setTraceId(traceId);
        cursor.setNextSequence(nextSequence);
        cursor.setRevision(revision);
        return cursor;
    }
}
