package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在当前状态迁移事务中分配游标并写入事件；无外层事务时自动创建短事务。 */
@Component
public class WorkflowEventWriteTransaction {
    private final IWorkflowRunEventDao eventDao;
    private final IWorkflowEventCursorRepository cursorRepository;

    public WorkflowEventWriteTransaction(IWorkflowRunEventDao eventDao,
                                         IWorkflowEventCursorRepository cursorRepository) {
        this.eventDao = eventDao;
        this.cursorRepository = cursorRepository;
    }

    /**
     * 必须加入调用方事务：新建 Run 时 chat_run 尚未提交，若另开 REQUIRES_NEW 再从 chat_run
     * 初始化游标，新连接会等待外层事务释放行锁，最终触发 JDBC socketTimeout，甚至导致回滚失败。
     * 运行期调用没有外层事务时，Spring 仍会为本方法创建独立短事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public WorkflowRunEventEntity appendOnce(WorkflowRunEventEntity event) {
        long sequence = cursorRepository.allocate(event.getTenantId(), event.getUserId(),
                event.getRunId(), event.getTraceId(), event.getEventType());
        event.setSequence(sequence);
        if (eventDao.insert(toPO(event)) != 1) {
            throw new AppException("WORKFLOW_EVENT_WRITE_FAILED", "工作流事件写入失败");
        }
        return event;
    }

    private WorkflowRunEventPO toPO(WorkflowRunEventEntity event) {
        WorkflowRunEventPO po = new WorkflowRunEventPO();
        po.setTenantId(event.getTenantId()); po.setUserId(event.getUserId()); po.setRunId(event.getRunId());
        po.setEventId(event.getEventId()); po.setSequence(event.getSequence()); po.setSchemaVersion(event.getSchemaVersion());
        po.setEventType(event.getEventType()); po.setNodeExecutionId(event.getNodeExecutionId()); po.setNodeId(event.getNodeId());
        po.setPayloadJson(event.getPayloadJson()); po.setTraceId(event.getTraceId()); po.setOccurredAt(event.getOccurredAt());
        po.setExpiresAt(event.getExpiresAt());
        return po;
    }
}
