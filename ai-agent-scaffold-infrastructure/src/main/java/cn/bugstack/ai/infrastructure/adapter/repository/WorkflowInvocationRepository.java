package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowInvocationRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowInvocationEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowInvocationDao;
import cn.bugstack.ai.infrastructure.dao.po.WorkflowInvocationPO;
import org.springframework.stereotype.Repository;

/** 智能工作流调用账本 MyBatis 仓储。 */
@Repository
public class WorkflowInvocationRepository implements IWorkflowInvocationRepository {
    private final IWorkflowInvocationDao dao;
    public WorkflowInvocationRepository(IWorkflowInvocationDao dao) { this.dao = dao; }
    @Override public int insertIgnore(WorkflowInvocationEntity value) {
        WorkflowInvocationPO po = new WorkflowInvocationPO();
        po.setTenantId(value.getTenantId()); po.setRunId(value.getRunId()); po.setInvocationId(value.getInvocationId());
        po.setIdempotencyKey(value.getIdempotencyKey()); po.setNodeExecutionId(value.getNodeExecutionId());
        po.setInvocationType(value.getInvocationType()); po.setReplayClass(value.getReplayClass()); po.setStatus(value.getStatus());
        po.setDownstreamRequestId(value.getDownstreamRequestId()); po.setTraceId(value.getTraceId()); po.setStartedAt(value.getStartedAt());
        return dao.insertIgnore(po);
    }
    @Override public int finish(String tenantId, String invocationId, String status, String downstreamRequestId) {
        return dao.finish(tenantId, invocationId, status, downstreamRequestId);
    }
}
