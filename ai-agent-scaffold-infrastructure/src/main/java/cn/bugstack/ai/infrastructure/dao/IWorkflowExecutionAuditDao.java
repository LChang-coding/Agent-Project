package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;

/** 智能工作流执行审计 DAO。 */
public interface IWorkflowExecutionAuditDao {
    int insertNode(WorkflowNodeExecutionEntity execution);
    int completeNode(WorkflowNodeExecutionEntity execution);
    int insertRoute(WorkflowRouteDecisionEntity decision);
}
