package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.workflow.adapter.repository.IWorkflowExecutionAuditRepository;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;
import cn.bugstack.ai.infrastructure.dao.IWorkflowExecutionAuditDao;
import cn.bugstack.ai.types.exception.AppException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** 将节点与路由事实写入不可替换的审计表。 */
@Repository
public class WorkflowExecutionAuditRepository implements IWorkflowExecutionAuditRepository {
    /** 持久化节点执行事实和路由裁决事实的 DAO。 */
    private final IWorkflowExecutionAuditDao dao;

    /** 注入工作流执行审计 DAO。 */
    public WorkflowExecutionAuditRepository(IWorkflowExecutionAuditDao dao) { this.dao = dao; }

    /** 创建节点开始记录；未写入一行视为审计失败，阻止无记录执行。 */
    @Override
    public void startNode(WorkflowNodeExecutionEntity execution) {
        if (dao.insertNode(execution) != 1) throw new AppException("WORKFLOW_NODE_AUDIT_FAILED", "节点执行记录创建失败");
    }

    /** 收口节点执行记录；条件更新失败说明执行事实已经失效或发生冲突。 */
    @Override
    public void completeNode(WorkflowNodeExecutionEntity execution) {
        if (dao.completeNode(execution) != 1) throw new AppException("WORKFLOW_NODE_AUDIT_FAILED", "节点执行记录收口失败");
    }

    /** 将运行中节点批量标记为取消，返回实际更新数量。 */
    @Override
    public int cancelRunningNodes(String tenantId, String runId, LocalDateTime finishedAt) {
        return dao.cancelRunningNodes(tenantId, runId, finishedAt);
    }

    /** 保存运行时已经作出的权威路由裁决，拒绝静默丢失。 */
    @Override
    public void decideRoute(WorkflowRouteDecisionEntity decision) {
        if (dao.insertRoute(decision) != 1) throw new AppException("WORKFLOW_ROUTE_AUDIT_FAILED", "路由裁决记录创建失败");
    }
}
