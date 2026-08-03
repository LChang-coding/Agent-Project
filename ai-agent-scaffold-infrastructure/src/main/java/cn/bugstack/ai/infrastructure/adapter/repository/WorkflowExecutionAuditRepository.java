package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowExecutionAuditRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowExecutionAuditDao;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Repository;

/** 将节点与路由事实写入不可替换的审计表。 */
@Repository
public class WorkflowExecutionAuditRepository implements IWorkflowExecutionAuditRepository {
    private final IWorkflowExecutionAuditDao dao;

    public WorkflowExecutionAuditRepository(IWorkflowExecutionAuditDao dao) { this.dao = dao; }

    @Override
    public void startNode(WorkflowNodeExecutionEntity execution) {
        if (dao.insertNode(execution) != 1) throw new AppException("WORKFLOW_NODE_AUDIT_FAILED", "节点执行记录创建失败");
    }

    @Override
    public void completeNode(WorkflowNodeExecutionEntity execution) {
        if (dao.completeNode(execution) != 1) throw new AppException("WORKFLOW_NODE_AUDIT_FAILED", "节点执行记录收口失败");
    }

    @Override
    public void decideRoute(WorkflowRouteDecisionEntity decision) {
        if (dao.insertRoute(decision) != 1) throw new AppException("WORKFLOW_ROUTE_AUDIT_FAILED", "路由裁决记录创建失败");
    }
}
