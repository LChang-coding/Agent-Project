package cn.bugstack.ai.domain.workflow.adapter.repository;

import cn.bugstack.ai.domain.workflow.model.entity.WorkflowRouteIntentEntity;

import java.time.LocalDateTime;

/** 工作流路由意图持久化端口。 */
public interface IWorkflowRouteIntentRepository {

    /**
     * 尝试登记当前节点的路由意图。
     *
     * @param intent 已绑定运行、节点、定义版本和函数调用的路由意图
     * @return 1 表示首次登记成功，0 表示节点或函数调用的唯一约束已被占用
     */
    int claim(WorkflowRouteIntentEntity intent);

    /**
     * 查询某次节点执行已经登记的路由意图。
     *
     * @param tenantId 运行所属租户
     * @param runId 路由意图所属运行
     * @param nodeExecutionId 产生路由意图的逻辑节点执行
     * @return 已登记意图；节点尚未选择路由时返回空
     */
    WorkflowRouteIntentEntity queryByNode(String tenantId, String runId, String nodeExecutionId);

    /**
     * 按模型函数调用标识查询意图，用于相同调用的幂等重放。
     *
     * @param tenantId 函数调用所属租户
     * @param functionCallId 模型生成的函数调用标识
     * @return 对应路由意图；首次调用时返回空
     */
    WorkflowRouteIntentEntity queryByFunctionCall(String tenantId, String functionCallId);

    /**
     * 将待处理意图原子更新为已消费。
     *
     * @param tenantId 运行所属租户
     * @param runId 路由意图所属运行
     * @param nodeExecutionId 产生路由意图的逻辑节点执行
     * @param consumedAt 实际形成权威路由裁决的时间
     * @return 1 表示本次完成状态转换，0 表示不存在待处理意图或已被其他执行者消费
     */
    int consume(String tenantId, String runId, String nodeExecutionId, LocalDateTime consumedAt);
}
