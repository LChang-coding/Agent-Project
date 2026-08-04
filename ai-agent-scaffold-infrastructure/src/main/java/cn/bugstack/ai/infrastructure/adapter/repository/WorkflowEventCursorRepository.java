package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import cn.bugstack.ai.infrastructure.dao.IWorkflowEventCursorDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowEventCursorPO;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 以通用游标行锁串行分配 workflow-event-v1 序号。 */
@Repository
public class WorkflowEventCursorRepository implements IWorkflowEventCursorRepository {

    private final IWorkflowEventCursorDao cursorDao;

    public WorkflowEventCursorRepository(IWorkflowEventCursorDao cursorDao) {
        this.cursorDao = cursorDao;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long allocate(String tenantId, String userId, String runId, String traceId, String eventType) {
        requireText(tenantId);
        requireText(userId);
        requireText(runId);
        requireText(traceId);
        requireText(eventType);
        cursorDao.insertFromWorkflowChatRun(tenantId, userId, runId);
        WorkflowEventCursorPO cursor = cursorDao.lockCursor(tenantId, userId, runId);
        if (cursor == null) {
            throw new AppException("WORKFLOW_RUN_NOT_FOUND", "工作流运行不存在或无权访问");
        }
        if (!traceId.equals(cursor.getTraceId())) {
            throw new AppException("WORKFLOW_TRACE_MISMATCH", "事件 traceId 与运行根链路不一致");
        }
        if (cursor.getTerminalEventType() != null) {
            throw new AppException("WORKFLOW_EVENT_AFTER_TERMINAL",
                    "工作流已写入终态事件: " + cursor.getTerminalEventType());
        }
        if (cursor.getNextSequence() == null || cursor.getRevision() == null
                || cursor.getNextSequence() < 1 || cursor.getNextSequence() == Long.MAX_VALUE) {
            throw new AppException("WORKFLOW_EVENT_CURSOR_INVALID", "工作流事件序号游标异常");
        }
        long sequence = cursor.getNextSequence();
        boolean terminal = terminal(eventType);
        int advanced = terminal
                ? cursorDao.advanceTerminalSequence(tenantId, userId, runId, traceId, eventType, cursor.getRevision())
                : cursorDao.advanceSequence(tenantId, userId, runId, traceId, cursor.getRevision());
        if (advanced != 1) {
            throw new AppException("WORKFLOW_EVENT_CONCURRENT_MODIFICATION", "事件序号分配发生并发冲突");
        }
        cursorDao.syncIntelligentRunSequence(tenantId, userId, runId, traceId, sequence + 1);
        return sequence;
    }

    private boolean terminal(String eventType) {
        return "WORKFLOW_COMPLETED".equals(eventType)
                || "WORKFLOW_FAILED".equals(eventType)
                || "WORKFLOW_CANCELLED".equals(eventType);
    }

    private void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException("WORKFLOW_EVENT_SCOPE_INVALID", "工作流事件作用域不完整");
        }
    }
}
