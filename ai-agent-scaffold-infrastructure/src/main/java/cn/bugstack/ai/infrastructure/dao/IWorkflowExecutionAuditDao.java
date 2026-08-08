package cn.bugstack.ai.infrastructure.dao;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** 智能工作流执行审计 DAO。 */
public interface IWorkflowExecutionAuditDao {
    /** 创建节点执行开始记录。 */
    int insertNode(WorkflowNodeExecutionEntity execution);

    /** 按执行标识收口节点状态、输出和结束时间。 */
    int completeNode(WorkflowNodeExecutionEntity execution);

    /** 将指定运行仍在执行的节点批量推进到取消状态。 */
    int cancelRunningNodes(@Param("tenantId") String tenantId, @Param("runId") String runId,
                           @Param("finishedAt") LocalDateTime finishedAt);

    /** 保存运行时最终采用的路由裁决。 */
    int insertRoute(WorkflowRouteDecisionEntity decision);
}
