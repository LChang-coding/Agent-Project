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

    /** 提供游标初始化、行锁读取和序号推进操作。 */
    private final IWorkflowEventCursorDao cursorDao;

    /** 注入事件游标 DAO。 */
    public WorkflowEventCursorRepository(IWorkflowEventCursorDao cursorDao) {
        this.cursorDao = cursorDao;
    }

    /**
     * 在一个短事务中锁定运行游标并分配唯一递增序号。
     * 终态事件会同时封闭游标，禁止后续事件越过终态继续写入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public long allocate(String tenantId, String userId, String runId, String traceId, String eventType) {
        requireText(tenantId);
        requireText(userId);
        requireText(runId);
        requireText(traceId);
        requireText(eventType);
        // 兼容尚未建立扩展游标的历史运行；已存在时由唯一键保证不重复创建。
        cursorDao.insertFromWorkflowChatRun(tenantId, userId, runId);
        // 行锁把同一运行的并发事件写入串行化，避免分配重复 sequence。
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
        // 终态推进会保存终态类型；普通推进只增加下一个可分配序号。
        int advanced = terminal
                ? cursorDao.advanceTerminalSequence(tenantId, userId, runId, traceId, eventType, cursor.getRevision())
                : cursorDao.advanceSequence(tenantId, userId, runId, traceId, cursor.getRevision());
        if (advanced != 1) {
            throw new AppException("WORKFLOW_EVENT_CONCURRENT_MODIFICATION", "事件序号分配发生并发冲突");
        }
        // 同步智能运行快照，确保恢复执行时使用与事件流一致的下一个序号。
        cursorDao.syncIntelligentRunSequence(tenantId, userId, runId, traceId, sequence + 1);
        return sequence;
    }

    /** 判断事件是否会封闭运行事件流。 */
    private boolean terminal(String eventType) {
        return "WORKFLOW_COMPLETED".equals(eventType)
                || "WORKFLOW_FAILED".equals(eventType)
                || "WORKFLOW_CANCELLED".equals(eventType);
    }

    /** 拒绝缺失的事件作用域，避免产生无法按租户和运行归属的记录。 */
    private void requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException("WORKFLOW_EVENT_SCOPE_INVALID", "工作流事件作用域不完整");
        }
    }
}
