package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 智能工作流执行审计 DAO。 */
public interface IWorkflowExecutionAuditDao {
    int insertNode(WorkflowNodeExecutionEntity execution);
    int completeNode(WorkflowNodeExecutionEntity execution);
    int cancelRunningNodes(@Param("tenantId") String tenantId, @Param("runId") String runId,
                           @Param("finishedAt") LocalDateTime finishedAt);
    int insertRoute(WorkflowRouteDecisionEntity decision);
}
