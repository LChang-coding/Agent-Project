package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 在独立短事务中分配游标并写入一条运行事件。 */
@Component
public class WorkflowEventWriteTransaction {
    private final IWorkflowRunEventDao eventDao;
    private final IWorkflowEventCursorRepository cursorRepository;

    public WorkflowEventWriteTransaction(IWorkflowRunEventDao eventDao,
                                         IWorkflowEventCursorRepository cursorRepository) {
        this.eventDao = eventDao;
        this.cursorRepository = cursorRepository;
    }

    /** 每次尝试使用新事务，避免游标行锁被外层长事务持有。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
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
