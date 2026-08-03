package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;

import java.time.LocalDateTime;

/** 持久化节点执行与路由裁决，供 Trace 反查和恢复审计。 */
public interface IWorkflowExecutionAuditRepository {
    void startNode(WorkflowNodeExecutionEntity execution);
    void completeNode(WorkflowNodeExecutionEntity execution);
    int cancelRunningNodes(String tenantId, String runId, LocalDateTime finishedAt);
    void decideRoute(WorkflowRouteDecisionEntity decision);
}
