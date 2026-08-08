package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowNodeExecutionEntity;
import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteDecisionEntity;

import java.time.LocalDateTime;

/** 持久化节点执行与路由裁决，供 Trace 反查和恢复审计。 */
public interface IWorkflowExecutionAuditRepository {

    /**
     * 记录节点开始执行；节点执行标识用于关联后续完成或取消更新。
     *
     * @param execution 已包含运行、节点、尝试次数和开始时间的执行记录
     */
    void startNode(WorkflowNodeExecutionEntity execution);

    /**
     * 保存节点的最终状态、输出摘要和结束时间。
     *
     * @param execution 已完成、失败或取消的节点执行记录
     */
    void completeNode(WorkflowNodeExecutionEntity execution);

    /**
     * 将指定运行中尚未结束的节点批量更新为取消。
     *
     * @param tenantId 运行所属租户
     * @param runId 被取消的工作流运行
     * @param finishedAt 统一记录的取消完成时间
     * @return 实际更新的运行中节点数量
     */
    int cancelRunningNodes(String tenantId, String runId, LocalDateTime finishedAt);

    /**
     * 保存节点完成后的权威路由裁决。
     *
     * @param decision 已解析到工作流边和目标节点的路由结果
     */
    void decideRoute(WorkflowRouteDecisionEntity decision);
}
