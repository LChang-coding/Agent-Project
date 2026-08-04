package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 使用通用 workflow 游标分配序号并持久化事件。 */
@Repository
public class WorkflowEventRepository implements IWorkflowEventRepository {

    private final IWorkflowRunEventDao eventDao;
    private final IWorkflowEventCursorRepository cursorRepository;

    public WorkflowEventRepository(IWorkflowRunEventDao eventDao,
                                   IWorkflowEventCursorRepository cursorRepository) {
        this.eventDao = eventDao;
        this.cursorRepository = cursorRepository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowRunEventEntity append(WorkflowRunEventEntity event) {
        long sequence = cursorRepository.allocate(event.getTenantId(), event.getUserId(),
                event.getRunId(), event.getTraceId(), event.getEventType());
        event.setSequence(sequence);
        if (eventDao.insert(toPO(event)) != 1) {
            throw new AppException("WORKFLOW_EVENT_WRITE_FAILED", "工作流事件写入失败");
        }
        return event;
    }

    @Override
    public List<WorkflowRunEventEntity> queryAfter(String tenantId, String userId, String runId,
                                                    long afterSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventDao.queryAfter(tenantId, userId, runId, Math.max(0, afterSequence), safeLimit)
                .stream().map(this::toEntity).toList();
    }

    @Override
    public Long queryOldestSequence(String tenantId, String userId, String runId) {
        return eventDao.queryOldestSequence(tenantId, userId, runId);
    }

    @Override
    public WorkflowRunEventEntity queryTerminal(String tenantId, String userId, String runId) {
        WorkflowRunEventPO terminal = eventDao.queryTerminal(tenantId, userId, runId);
        return terminal == null ? null : toEntity(terminal);
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

    private WorkflowRunEventEntity toEntity(WorkflowRunEventPO po) {
        return WorkflowRunEventEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId())
                .runId(po.getRunId()).eventId(po.getEventId()).sequence(po.getSequence())
                .schemaVersion(po.getSchemaVersion()).eventType(po.getEventType())
                .nodeExecutionId(po.getNodeExecutionId()).nodeId(po.getNodeId()).payloadJson(po.getPayloadJson())
                .traceId(po.getTraceId()).occurredAt(po.getOccurredAt()).expiresAt(po.getExpiresAt()).build();
    }
}
