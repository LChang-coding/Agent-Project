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

    /** 持久化工作流事件和查询事件流的 DAO。 */
    private final IWorkflowRunEventDao eventDao;
    /** 为同一运行分配严格递增事件序号的仓储。 */
    private final IWorkflowEventCursorRepository cursorRepository;

    /** 注入事件 DAO 和序号游标仓储。 */
    public WorkflowEventRepository(IWorkflowRunEventDao eventDao,
                                   IWorkflowEventCursorRepository cursorRepository) {
        this.eventDao = eventDao;
        this.cursorRepository = cursorRepository;
    }

    /** 分配序号并在同一事务中写入事件，任一步失败都会回滚。 */
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

    /**
     * 查询指定序号之后的事件。
     * 每次最多返回 1000 条，防止重放请求一次读取过多记录。
     */
    @Override
    public List<WorkflowRunEventEntity> queryAfter(String tenantId, String userId, String runId,
                                                    long afterSequence, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        return eventDao.queryAfter(tenantId, userId, runId, Math.max(0, afterSequence), safeLimit)
                .stream().map(this::toEntity).toList();
    }

    /** 查询当前仍可重放的最早事件序号，用于判断客户端游标是否已经过期。 */
    @Override
    public Long queryOldestSequence(String tenantId, String userId, String runId) {
        return eventDao.queryOldestSequence(tenantId, userId, runId);
    }

    /** 查询运行的终态事件，不存在终态时返回空值。 */
    @Override
    public WorkflowRunEventEntity queryTerminal(String tenantId, String userId, String runId) {
        WorkflowRunEventPO terminal = eventDao.queryTerminal(tenantId, userId, runId);
        return terminal == null ? null : toEntity(terminal);
    }

    /** 将领域事件的顺序、节点和载荷信息复制到持久化对象。 */
    private WorkflowRunEventPO toPO(WorkflowRunEventEntity event) {
        WorkflowRunEventPO po = new WorkflowRunEventPO();
        po.setTenantId(event.getTenantId()); po.setUserId(event.getUserId()); po.setRunId(event.getRunId());
        po.setEventId(event.getEventId()); po.setSequence(event.getSequence()); po.setSchemaVersion(event.getSchemaVersion());
        po.setEventType(event.getEventType()); po.setNodeExecutionId(event.getNodeExecutionId()); po.setNodeId(event.getNodeId());
        po.setPayloadJson(event.getPayloadJson()); po.setTraceId(event.getTraceId()); po.setOccurredAt(event.getOccurredAt());
        po.setExpiresAt(event.getExpiresAt());
        return po;
    }

    /** 将持久化事件恢复为对外重放使用的领域事件。 */
    private WorkflowRunEventEntity toEntity(WorkflowRunEventPO po) {
        return WorkflowRunEventEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId())
                .runId(po.getRunId()).eventId(po.getEventId()).sequence(po.getSequence())
                .schemaVersion(po.getSchemaVersion()).eventType(po.getEventType())
                .nodeExecutionId(po.getNodeExecutionId()).nodeId(po.getNodeId()).payloadJson(po.getPayloadJson())
                .traceId(po.getTraceId()).occurredAt(po.getOccurredAt()).expiresAt(po.getExpiresAt()).build();
    }
}
