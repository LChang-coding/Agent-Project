package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRunEventEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowRunEventDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowRunEventPO;
import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowEventCursorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** 使用通用 workflow 游标分配序号并持久化事件。 */
@Repository
public class WorkflowEventRepository implements IWorkflowEventRepository {

    /** 持久化工作流事件和查询事件流的 DAO。 */
    private final IWorkflowRunEventDao eventDao;
    /** 将游标分配和事件写入绑定到当前状态迁移事务，无外层事务时使用短事务。 */
    private final WorkflowEventWriteTransaction writeTransaction;

    /** 注入事件 DAO 和序号游标仓储。 */
    @Autowired
    public WorkflowEventRepository(IWorkflowRunEventDao eventDao,
                                   WorkflowEventWriteTransaction writeTransaction) {
        this.eventDao = eventDao;
        this.writeTransaction = writeTransaction;
    }

    /** 保留轻量单元测试构造方式。 */
    public WorkflowEventRepository(IWorkflowRunEventDao eventDao,
                                   IWorkflowEventCursorRepository cursorRepository) {
        this(eventDao, new WorkflowEventWriteTransaction(eventDao, cursorRepository));
    }

    /**
     * 死锁只在当前调用没有外层事务时做有界重试，此时每次调用都会重新开启短事务。
     * 已加入外层事务时，数据库死锁会使整个事务失效，必须向上抛出并由状态迁移整体重试，
     * 不能在已经 rollback-only 的事务里继续写事件。
     */
    @Override
    public WorkflowRunEventEntity append(WorkflowRunEventEntity event) {
        boolean joinedOuterTransaction = TransactionSynchronizationManager.isActualTransactionActive();
        for (int attempt = 1; ; attempt++) {
            try {
                return writeTransaction.appendOnce(event);
            } catch (DeadlockLoserDataAccessException exception) {
                if (joinedOuterTransaction || attempt >= 3) throw exception;
                long delayMillis = (20L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(16L);
                LockSupport.parkNanos(delayMillis * 1_000_000L);
                if (Thread.currentThread().isInterrupted()) throw exception;
            }
        }
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

    /** 将持久化事件恢复为对外重放使用的领域事件。 */
    private WorkflowRunEventEntity toEntity(WorkflowRunEventPO po) {
        return WorkflowRunEventEntity.builder().tenantId(po.getTenantId()).userId(po.getUserId())
                .runId(po.getRunId()).eventId(po.getEventId()).sequence(po.getSequence())
                .schemaVersion(po.getSchemaVersion()).eventType(po.getEventType())
                .nodeExecutionId(po.getNodeExecutionId()).nodeId(po.getNodeId()).payloadJson(po.getPayloadJson())
                .traceId(po.getTraceId()).occurredAt(po.getOccurredAt()).expiresAt(po.getExpiresAt()).build();
    }
}
