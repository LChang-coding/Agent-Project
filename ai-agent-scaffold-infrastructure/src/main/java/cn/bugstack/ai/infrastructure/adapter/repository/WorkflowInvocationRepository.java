package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowInvocationRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowInvocationDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowInvocationPO;
import org.springframework.stereotype.Repository;

/** 智能工作流调用账本 MyBatis 仓储。 */
@Repository
public class WorkflowInvocationRepository implements IWorkflowInvocationRepository {
    /** 读写工作流调用账本的 DAO。 */
    private final IWorkflowInvocationDao dao;

    /** 注入调用账本 DAO。 */
    public WorkflowInvocationRepository(IWorkflowInvocationDao dao) { this.dao = dao; }

    /**
     * 按幂等键登记一次外部调用。
     * 重复记录由数据库忽略，返回值供上层判断本次是否取得执行权。
     */
    @Override public int insertIgnore(WorkflowInvocationEntity value) {
        WorkflowInvocationPO po = new WorkflowInvocationPO();
        po.setTenantId(value.getTenantId()); po.setRunId(value.getRunId()); po.setInvocationId(value.getInvocationId());
        po.setIdempotencyKey(value.getIdempotencyKey()); po.setNodeExecutionId(value.getNodeExecutionId());
        po.setInvocationType(value.getInvocationType()); po.setReplayClass(value.getReplayClass()); po.setStatus(value.getStatus());
        po.setDownstreamRequestId(value.getDownstreamRequestId()); po.setTraceId(value.getTraceId()); po.setStartedAt(value.getStartedAt());
        return dao.insertIgnore(po);
    }

    /** 保存调用终态及下游请求标识，供恢复和审计使用。 */
    @Override public int finish(String tenantId, String invocationId, String status, String downstreamRequestId) {
        return dao.finish(tenantId, invocationId, status, downstreamRequestId);
    }
}
